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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.plugin.Credential;
import org.mariadb.jdbc.plugin.CredentialPlugin;

public class AwsIamAssumeRoleCredentialPlugin implements CredentialPlugin {

    private static final String TYPE = "AWS-IAM-ASSUME-ROLE";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final Map<KeyCache, IdentityExpire> CACHE = new ConcurrentHashMap<>();

    private final CredentialGeneratorFactory generatorFactory;

    private CredentialGenerator generator;
    private KeyCache key;

    public AwsIamAssumeRoleCredentialPlugin() {
        this((conf, userName, hostAddress) -> new AwsAssumeRoleCredentialGenerator(conf.nonMappedOptions(), userName,
                hostAddress));
    }

    AwsIamAssumeRoleCredentialPlugin(final CredentialGeneratorFactory generatorFactory) {
        this.generatorFactory = generatorFactory;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean mustUseSsl() {
        return true;
    }

    @Override
    public CredentialPlugin initialize(final Configuration conf, final String userName, final HostAddress hostAddress)
            throws SQLException {
        try {
            Class.forName("software.amazon.awssdk.services.sts.StsClient");
            Class.forName("software.amazon.awssdk.services.rds.RdsUtilities");
        } catch (final ClassNotFoundException e) {
            throw new SQLException(
                    "Identity plugin 'AWS-IAM-ASSUME-ROLE' requires 'software.amazon.awssdk:rds' and 'software.amazon.awssdk:sts' on the classpath");
        }

        generator = generatorFactory.create(conf, userName, hostAddress);
        key = new KeyCache(conf, userName, hostAddress);
        return this;
    }

    @Override
    public Credential get() {
        final IdentityExpire existing = CACHE.get(key);
        if (existing != null && existing.isValid()) {
            return existing.credential();
        }

        final Credential credential = generator.getToken();
        CACHE.put(key, new IdentityExpire(credential));
        return credential;
    }

    @FunctionalInterface
    interface CredentialGeneratorFactory {
        CredentialGenerator create(Configuration conf, String userName, HostAddress hostAddress)
                throws SQLException;
    }

    @FunctionalInterface
    interface CredentialGenerator {
        Credential getToken();
    }

    private static final class KeyCache {
        private final Configuration configuration;
        private final String userName;
        private final HostAddress hostAddress;

        private KeyCache(final Configuration configuration, final String userName, final HostAddress hostAddress) {
            this.configuration = configuration;
            this.userName = userName;
            this.hostAddress = hostAddress;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof KeyCache other)) {
                return false;
            }
            return Objects.equals(configuration, other.configuration)
                    && Objects.equals(userName, other.userName)
                    && Objects.equals(hostAddress, other.hostAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(configuration, userName, hostAddress);
        }
    }

    private static final class IdentityExpire {
        private final Instant expiresAt;
        private final Credential credential;

        private IdentityExpire(final Credential credential) {
            this.expiresAt = Instant.now().plus(TOKEN_TTL);
            this.credential = credential;
        }

        private boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }

        private Credential credential() {
            return credential;
        }
    }
}
