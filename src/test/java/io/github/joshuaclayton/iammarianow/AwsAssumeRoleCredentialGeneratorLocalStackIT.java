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

import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.plugin.Credential;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Exercises the real STS {@code AssumeRole} call path through {@link AwsAssumeRoleCredentialGenerator}
 * against LocalStack, a real open-source AWS emulator, instead of a hand-written mock. The
 * {@code stsEndpointOverride} property is what makes this possible without changing production
 * behaviour: it is the same hook a real deployment would use to point at a private STS VPC endpoint.
 *
 * <p>Requires a local Docker daemon. Runs only via {@code mvn verify -Pintegration}; it is excluded
 * from the default {@code mvn test} build.
 */
@Testcontainers
class AwsAssumeRoleCredentialGeneratorLocalStackIT {

    @Container
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.STS);

    @BeforeAll
    static void isolateFromLocalAwsConfig() {
        System.setProperty("aws.configFile", "/dev/null");
        System.setProperty("aws.sharedCredentialsFile", "/dev/null");
    }

    @Test
    void generatorAssumesRoleViaStsAndProducesAToken() throws Exception {
        final Properties options = new Properties();
        options.setProperty("assumeRoleArn", "arn:aws:iam::000000000000:role/iam-maria-now-test");
        options.setProperty("region", LOCALSTACK.getRegion());
        options.setProperty("stsEndpointOverride",
                LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.STS).toString());

        final AwsAssumeRoleCredentialGenerator generator = new AwsAssumeRoleCredentialGenerator(
                options, "db_app", HostAddress.from("db.example.com", 3306),
                StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())),
                Region.of(LOCALSTACK.getRegion()));

        final Credential credential = generator.getToken();

        assertThat(credential.getUser()).isEqualTo("db_app");
        assertThat(credential.getPassword())
                .startsWith("db.example.com:3306/?")
                .contains("Action=connect")
                .contains("DBUser=db_app");
    }
}
