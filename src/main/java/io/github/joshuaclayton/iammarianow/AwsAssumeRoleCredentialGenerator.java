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

import java.net.URI;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.plugin.Credential;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;

final class AwsAssumeRoleCredentialGenerator implements AwsIamAssumeRoleCredentialPlugin.CredentialGenerator {

    static final String ASSUME_ROLE_ARN_PROPERTY = "assumeRoleArn";
    static final String REGION_PROPERTY = "region";
    static final String ROLE_SESSION_NAME_PROPERTY = "roleSessionName";
    static final String DEFAULT_ROLE_SESSION_NAME = "iam-maria-now";
    static final String STS_ENDPOINT_OVERRIDE_PROPERTY = "stsEndpointOverride";

    private final String userName;
    private final String authenticationToken;

    AwsAssumeRoleCredentialGenerator(final Properties options, final String userName, final HostAddress hostAddress)
            throws SQLException {
        this(options, userName, hostAddress, createBaseCredentialsProvider(), requireAssumeRoleArn(options),
                resolveRegion(options));
    }

    AwsAssumeRoleCredentialGenerator(final Properties options, final String userName, final HostAddress hostAddress,
            final AwsCredentialsProvider baseCredentialsProvider, final Region region) throws SQLException {
        this(options, userName, hostAddress, baseCredentialsProvider, requireAssumeRoleArn(options),
                region);
    }

    AwsAssumeRoleCredentialGenerator(final Properties options, final String userName, final HostAddress hostAddress,
            final AwsCredentialsProvider baseCredentialsProvider, final String assumeRoleArn, final Region region)
            throws SQLException {
        this.userName = userName;

        if (assumeRoleArn == null || assumeRoleArn.isBlank()) {
            throw new SQLException("Identity plugin 'AWS-IAM-ASSUME-ROLE' requires the 'assumeRoleArn' connection property");
        }

        final String roleSessionName = resolveRoleSessionName(options);
        final StsClientBuilder stsClientBuilder = StsClient.builder()
                .credentialsProvider(baseCredentialsProvider)
                .region(region);
        resolveStsEndpointOverride(options).ifPresent(stsClientBuilder::endpointOverride);
        final StsClient stsClient = stsClientBuilder.build();
        final AwsCredentialsProvider assumedRoleCredentialsProvider = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(request -> request.roleArn(assumeRoleArn).roleSessionName(roleSessionName))
                .build();
        final RdsUtilities utilities = RdsUtilities.builder()
                .credentialsProvider(assumedRoleCredentialsProvider)
                .region(region)
                .build();
        authenticationToken = utilities.generateAuthenticationToken(request -> request
                .hostname(hostAddress.host)
                .port(hostAddress.port)
                .username(userName)
                .credentialsProvider(assumedRoleCredentialsProvider));
    }

    public Credential getToken() {
        return new Credential(userName, authenticationToken);
    }

    static AwsCredentialsProvider createBaseCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    static String requireAssumeRoleArn(final Properties options) throws SQLException {
        final String assumeRoleArn = options.getProperty(ASSUME_ROLE_ARN_PROPERTY);
        if (assumeRoleArn == null || assumeRoleArn.isBlank()) {
            throw new SQLException("Identity plugin 'AWS-IAM-ASSUME-ROLE' requires the 'assumeRoleArn' connection property");
        }
        return assumeRoleArn;
    }

    static Region resolveRegion(final Properties options) {
        final String region = options.getProperty(REGION_PROPERTY);
        return region != null ? Region.of(region) : new DefaultAwsRegionProviderChain().getRegion();
    }

    static String resolveRoleSessionName(final Properties options) {
        final String roleSessionName = options.getProperty(ROLE_SESSION_NAME_PROPERTY);
        return roleSessionName != null && !roleSessionName.isBlank() ? roleSessionName : DEFAULT_ROLE_SESSION_NAME;
    }

    static Optional<URI> resolveStsEndpointOverride(final Properties options) {
        final String endpointOverride = options.getProperty(STS_ENDPOINT_OVERRIDE_PROPERTY);
        return endpointOverride != null && !endpointOverride.isBlank()
                ? Optional.of(URI.create(endpointOverride))
                : Optional.empty();
    }
}
