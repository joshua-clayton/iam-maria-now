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

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * {@link RdsUtilities#generateAuthenticationToken} only performs local SigV4 presigning
 * (no network call), so these tests exercise the real AWS SDK signing code directly with
 * static test credentials, without needing any AWS service, mock, or emulator.
 */
class RdsAuthTokenGenerationTest {

    private final RdsUtilities utilities = RdsUtilities.builder()
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIATESTACCESSKEY", "test-secret-key")))
            .region(Region.US_WEST_2)
            .build();

    @Test
    void generatesAWellFormedPresignedConnectUrl() {
        final String token = utilities.generateAuthenticationToken(request -> request
                .hostname("db.example.com")
                .port(3306)
                .username("db_app"));

        assertThat(token).startsWith("db.example.com:3306/?");
        assertThat(token).contains("Action=connect");
        assertThat(token).contains("DBUser=db_app");
        assertThat(token).contains("X-Amz-Credential=AKIATESTACCESSKEY");
        assertThat(token).contains("us-west-2%2Frds-db%2Faws4_request");
    }

    @Test
    void tokenReflectsTheRequestedUserAndHost() {
        final String firstToken = utilities.generateAuthenticationToken(request -> request
                .hostname("db.example.com")
                .port(3306)
                .username("db_app"));
        final String secondToken = utilities.generateAuthenticationToken(request -> request
                .hostname("other-db.example.com")
                .port(3306)
                .username("other_app"));

        assertThat(firstToken).contains("db.example.com").contains("DBUser=db_app");
        assertThat(secondToken).contains("other-db.example.com").contains("DBUser=other_app");
        assertThat(firstToken).isNotEqualTo(secondToken);
    }
}
