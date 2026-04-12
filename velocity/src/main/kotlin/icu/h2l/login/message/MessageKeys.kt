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

package icu.h2l.login.message

object MessageKeys {
    object Common {
        const val ONLY_PLAYER = "common.only-player"
        const val NO_PERMISSION = "common.no-permission"
        const val PLAYER_STATE_UNAVAILABLE = "common.player-state-unavailable"
    }

    object Chat {
        const val MUST_VERIFY_BEFORE_CHAT = "chat.must-verify-before-chat"
        const val ONLY_ALLOWED_COMMANDS = "chat.only-allowed-commands"
        const val WAITING_AREA_ONLY = "chat.waiting-area-only"
    }

    object BindCode {
        const val COMMAND_USAGE = "bindcode.command-usage"
        const val COMMAND_USE_USAGE = "bindcode.command-use-usage"
        const val GENERATE_NO_PROFILE = "bindcode.generate.no-profile"
        const val GENERATE_FAILED = "bindcode.generate.failed"
        const val GENERATED_HEADER = "bindcode.generated.header"
        const val GENERATED_HOVER_COPY = "bindcode.generated.hover-copy"
        const val GENERATED_FOOTER = "bindcode.generated.footer"
        const val USE_VERIFY_FIRST = "bindcode.use.verify-first"
        const val USE_ALREADY_BOUND = "bindcode.use.already-bound"
        const val USE_NO_CREDENTIALS = "bindcode.use.no-credentials"
        const val USE_EMPTY = "bindcode.use.empty"
        const val USE_INVALID = "bindcode.use.invalid"
        const val USE_BIND_FAILED = "bindcode.use.bind-failed"
        const val USE_BIND_FAILED_WITH_REASON = "bindcode.use.bind-failed-with-reason"
        const val USE_CONSUME_FAILED = "bindcode.use.consume-failed"
        const val USE_ATTACH_FAILED = "bindcode.use.attach-failed"
        const val USE_SUCCESS = "bindcode.use.success"
    }

    object HzlCommand {
        const val USAGE_RELOAD = "hzl.usage.reload"
        const val USAGE_RE = "hzl.usage.re"
        const val USAGE_BINDCODE_GENERATE = "hzl.usage.bindcode-generate"
        const val USAGE_BINDCODE_USE = "hzl.usage.bindcode-use"
        const val USAGE_UUID = "hzl.usage.uuid"
        const val RELOADED = "hzl.reloaded"
        const val REAUTH_START = "hzl.reauth-start"
        const val AUTH_FLOW_UNAVAILABLE = "hzl.auth-flow-unavailable"
        const val UUID_PROXY_PLAYER = "hzl.uuid.proxy-player"
        const val UUID_CLIENT_ORIGINAL = "hzl.uuid.client-original"
        const val UUID_HYPER_PLAYER = "hzl.uuid.hyper-player"
        const val UUID_PROFILE = "hzl.uuid.profile"
        const val UUID_PROFILE_NULL = "hzl.uuid.profile-null"
    }

    object Player {
        const val VERIFIED_UNBOUND = "player.verified-unbound"
        const val PROFILE_CONFLICT_SELF = "player.profile-conflict-self"
        const val PROFILE_CONFLICT_OTHER = "player.profile-conflict-other"
    }

    object BackendAuth {
        const val NO_AUTH_SERVER = "backend-auth.no-auth-server"
        const val ENTER_FAILED_EXCEPTION = "backend-auth.enter-failed.exception"
        const val ENTER_FAILED_REASON = "backend-auth.enter-failed.reason"
        const val MISCONFIGURED_DISCONNECT = "backend-auth.misconfigured-disconnect"
        const val UNAVAILABLE_DISCONNECT = "backend-auth.unavailable-disconnect"
        const val MUST_VERIFY_BEFORE_TRANSFER = "backend-auth.must-verify-before-transfer"
        const val EXIT_NO_TARGET = "backend-auth.exit.no-target"
        const val EXIT_SERVER_MISSING = "backend-auth.exit.server-missing"
        const val EXIT_FAILURE_EXCEPTION = "backend-auth.exit.failure-exception"
        const val EXIT_FAILURE_REASON = "backend-auth.exit.failure-reason"
        const val VERIFIED_NO_TARGET = "backend-auth.verified.no-target"
        const val VERIFIED_SERVER_MISSING = "backend-auth.verified.server-missing"
        const val VERIFIED_FAILURE_EXCEPTION = "backend-auth.verified.failure-exception"
        const val VERIFIED_FAILURE_REASON = "backend-auth.verified.failure-reason"
    }

    object Exit {
        const val STILL_IN_WAITING_AREA = "exit.still-in-waiting-area"
        const val NOT_IN_WAITING_AREA = "exit.not-in-waiting-area"
        const val ATTEMPTED = "exit.attempted"
    }
}

