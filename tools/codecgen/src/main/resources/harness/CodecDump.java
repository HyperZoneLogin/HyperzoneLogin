/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagManager;

import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * 由 backend-hzlimbo 的 codecgen 工具在运行时针对反混淆后的 server.jar 编译并执行。
 *
 * 引导原版 RegistryAccess（含 WORLDGEN 层），绑定 tags，用 RegistrySynchronization 序列化
 * registry codec，写成与 NanoLimbo 一致的无根名 + gzip 的 NBT（codec_*.nbt / tags_*.nbt）。
 */
public final class CodecDump {

    private static final Method HOLDER_SET_BIND = holderSetBind();

    private CodecDump() {
    }

    public static void main(String[] args) throws Exception {
        Path codecOut = Path.of(args[0]);
        Path tagsOut = Path.of(args[1]);

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
        VanillaPackResources vanilla = ServerPacksSource.createVanillaPackSource();
        MultiPackResourceManager resourceManager =
            new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla));
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(
            resourceManager, layered.compositeAccess(), RegistryDataLoader.WORLDGEN_REGISTRIES);
        LayeredRegistryAccess<RegistryLayer> full =
            layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen);
        RegistryAccess.Frozen frozen = full.compositeAccess();

        bindAllTags(frozen, resourceManager);

        writeNamelessGzip(buildCodec(frozen), codecOut);
        writeNamelessGzip(buildTags(frozen), tagsOut);
    }

    private static Method holderSetBind() {
        try {
            Method m = HolderSet.Named.class.getDeclaredMethod("bind", List.class);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void bindAllTags(RegistryAccess.Frozen frozen, MultiPackResourceManager resourceManager)
        throws Exception {
        for (RegistryAccess.RegistryEntry<?> entry : frozen.registries().toList()) {
            if (isNetworkableTagRegistry(entry.key())) {
                bindTags(entry.value(), resourceManager);
            }
        }
    }

    private static boolean isNetworkableTagRegistry(ResourceKey<?> key) {
        String name = key.location().toString();
        // 客户端只认识静态注册表 + dimension_type / worldgen:biome；
        // 其余 worldgen/*（structure、world_preset、flat_level_generator_preset 等）是服务端内部数据。
        return !name.startsWith("minecraft:worldgen/") || name.equals("minecraft:worldgen/biome");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> void bindTags(Registry<T> registry, MultiPackResourceManager resourceManager) throws Exception {
        String tagDir = TagManager.getTagDir(registry.key());
        TagLoader<T> loader = new TagLoader<>(
            (ResourceLocation res) -> registry.getHolder(res).map(Holder.Reference::value),
            tagDir);
        Map<ResourceLocation, Collection<T>> built = loader.loadAndBuild(resourceManager);
        for (Map.Entry<ResourceLocation, Collection<T>> entry : built.entrySet()) {
            TagKey<T> tagKey = TagKey.create(registry.key(), entry.getKey());
            List<Holder<T>> holders = entry.getValue().stream()
                .map(registry::wrapAsHolder)
                .collect(Collectors.toList());
            HOLDER_SET_BIND.invoke(registry.getOrCreateTag(tagKey), holders);
        }
    }

    private static CompoundTag buildCodec(RegistryAccess.Frozen frozen) {
        CompoundTag codec = new CompoundTag();
        RegistrySynchronization.packRegistries(
            RegistryOps.create(NbtOps.INSTANCE, frozen),
            frozen,
            Set.of(),
            (ResourceKey<? extends Registry<?>> key, List<RegistrySynchronization.PackedRegistryEntry> entries) -> {
                CompoundTag registryTag = new CompoundTag();
                registryTag.putString("type", key.location().toString());
                ListTag value = new ListTag();
                int id = 0;
                for (RegistrySynchronization.PackedRegistryEntry entry : entries) {
                    CompoundTag e = new CompoundTag();
                    e.putString("name", entry.id().toString());
                    e.putInt("id", id++);
                    entry.data().ifPresent(d -> e.put("element", d));
                    value.add(e);
                }
                registryTag.put("value", value);
                codec.put(key.location().toString(), registryTag);
            });
        return codec;
    }

    private static CompoundTag buildTags(RegistryAccess.Frozen frozen) {
        CompoundTag tags = new CompoundTag();
        for (RegistryAccess.RegistryEntry<?> entry : frozen.registries().toList()) {
            if (isNetworkableTagRegistry(entry.key())) {
                collectTags(entry.value(), entry.key().location().toString(), tags);
            }
        }
        return tags;
    }

    private static <T> void collectTags(Registry<T> registry, String name, CompoundTag out) {
        CompoundTag registryTags = new CompoundTag();
        registry.getTags().forEach(pair -> {
            String tagName = pair.getFirst().location().toString();
            ListTag ids = new ListTag();
            for (Holder<T> holder : pair.getSecond()) {
                ids.add(IntTag.valueOf(registry.getId(holder.value())));
            }
            registryTags.put(tagName, ids);
        });
        if (!registryTags.isEmpty()) {
            out.put(name, registryTags);
        }
    }

    private static void writeNamelessGzip(CompoundTag tag, Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (var out = new GZIPOutputStream(Files.newOutputStream(path))) {
            NbtIo.writeUnnamedTag(tag, new DataOutputStream(out));
        }
    }
}
