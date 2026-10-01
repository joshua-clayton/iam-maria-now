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

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.plugin.Credential;

class AwsIamAssumeRoleCredentialPluginTest {

    @Test
    void exposesExpectedPluginTypeAndSslRequirement() {
        final AwsIamAssumeRoleCredentialPlugin plugin = new AwsIamAssumeRoleCredentialPlugin();

        assertThat(plugin.type()).isEqualTo("AWS-IAM-ASSUME-ROLE");
        assertThat(plugin.mustUseSsl()).isTrue();
    }

    @Test
    void cachesGeneratedCredentialsForTheSameConfiguration() throws SQLException {
        final AtomicInteger calls = new AtomicInteger();
        final AwsIamAssumeRoleCredentialPlugin plugin = new AwsIamAssumeRoleCredentialPlugin(
                (conf, userName, hostAddress) -> {
                    calls.incrementAndGet();
                    return () -> new Credential(userName, "token-" + calls.get());
                });

        final Configuration configuration = Configuration.parse(
                "jdbc:mariadb://db.example.com:3306/app?user=db_app"
                        + "&credentialType=AWS-IAM-ASSUME-ROLE"
                        + "&assumeRoleArn=arn:aws:iam::123456789012:role/db-operator");
        plugin.initialize(configuration, configuration.user(), configuration.addresses().get(0));

        final Credential first = plugin.get();
        final Credential second = plugin.get();

        assertThat(first.getUser()).isEqualTo("db_app");
        assertThat(first.getPassword()).isEqualTo("token-1");
        assertThat(second.getPassword()).isEqualTo("token-1");
        assertThat(calls).hasValue(1);
    }

    @Test
    void initializePassesTheConfiguredUserAndHostToTheGenerator() throws SQLException {
        final HostAddress expectedHost = HostAddress.from("db.example.com", 3306);
        final StringBuilder observed = new StringBuilder();
        final AwsIamAssumeRoleCredentialPlugin plugin = new AwsIamAssumeRoleCredentialPlugin(
                (conf, userName, hostAddress) -> {
                    observed.append(userName).append('@').append(hostAddress.host).append(':').append(hostAddress.port);
                    return () -> new Credential(userName, "token");
                });

        final Configuration configuration = Configuration.parse(
                "jdbc:mariadb://db.example.com:3306/app?user=db_app"
                        + "&credentialType=AWS-IAM-ASSUME-ROLE"
                        + "&assumeRoleArn=arn:aws:iam::123456789012:role/db-operator");

        plugin.initialize(configuration, "db_app", expectedHost);

        assertThat(observed.toString()).isEqualTo("db_app@db.example.com:3306");
    }
}
