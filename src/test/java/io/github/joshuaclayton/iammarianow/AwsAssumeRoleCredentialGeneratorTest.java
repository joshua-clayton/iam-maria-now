/*
 * iam-maria-now
 * Copyright (C) 2026 Joshua Clayton
 *
 * This library is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package io.github.joshuaclayton.iammarianow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.HostAddress;

class AwsAssumeRoleCredentialGeneratorTest {

    @Test
    void constructorRequiresAssumeRoleArn() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> new AwsAssumeRoleCredentialGenerator(new Properties(), "db_app",
                        HostAddress.from("db.example.com", 3306)))
                .withMessageContaining("assumeRoleArn");
    }

    @Test
    void resolveRoleSessionNameDefaultsWhenNotConfigured() {
        assertThat(AwsAssumeRoleCredentialGenerator.resolveRoleSessionName(new Properties()))
                .isEqualTo("iam-maria-now");
    }

    @Test
    void resolveRoleSessionNameUsesConfiguredValue() {
        final Properties options = new Properties();
        options.setProperty("roleSessionName", "my-custom-session");

        assertThat(AwsAssumeRoleCredentialGenerator.resolveRoleSessionName(options))
                .isEqualTo("my-custom-session");
    }

    @Test
    void resolveStsEndpointOverrideIsEmptyWhenNotConfigured() {
        assertThat(AwsAssumeRoleCredentialGenerator.resolveStsEndpointOverride(new Properties())).isEmpty();
    }

    @Test
    void resolveStsEndpointOverrideUsesConfiguredValue() {
        final Properties options = new Properties();
        options.setProperty("stsEndpointOverride", "https://sts.example.com:4566");

        assertThat(AwsAssumeRoleCredentialGenerator.resolveStsEndpointOverride(options))
                .isEqualTo(Optional.of(URI.create("https://sts.example.com:4566")));
    }
}
