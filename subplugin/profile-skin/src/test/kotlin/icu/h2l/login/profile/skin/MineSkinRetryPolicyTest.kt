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

package icu.h2l.login.profile.skin

import icu.h2l.login.profile.skin.service.shouldRetryUploadAfterUrlReadFailure
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MineSkinRetryPolicyTest {
    @Test
    fun `invalid image with undefined file size should trigger upload retry`() {
        val body = """
            {"success":false,"errorType":"GeneratorError","errorCode":"invalid_image","error":"Invalid image file size: undefined","breadcrumb":"16b754f8","nextRequest":6,"delay":6,"delayInfo":{"seconds":6,"millis":6000}}
        """.trimIndent()

        assertTrue(shouldRetryUploadAfterUrlReadFailure(400, body))
    }

    @Test
    fun `other invalid image errors should not trigger upload retry`() {
        val body = """
            {"success":false,"errorType":"GeneratorError","errorCode":"invalid_image","error":"Skin image decode failed","breadcrumb":"abc123"}
        """.trimIndent()

        assertFalse(shouldRetryUploadAfterUrlReadFailure(400, body))
    }

    @Test
    fun `non 400 responses should not trigger upload retry`() {
        val body = """
            {"success":false,"errorType":"GeneratorError","errorCode":"invalid_image","error":"Invalid image file size: undefined"}
        """.trimIndent()

        assertFalse(shouldRetryUploadAfterUrlReadFailure(429, body))
    }
}

