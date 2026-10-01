# iam-maria-now

A [MariaDB Connector/J](https://mariadb.com/kb/en/about-mariadb-connector-j/) `CredentialPlugin` that generates
AWS RDS/Aurora IAM authentication tokens using credentials obtained by **assuming an IAM role via STS**, instead
of the connecting process's own identity.

MariaDB Connector/J ships a built-in `AWS-IAM`
[credential plugin](https://mariadb.com/kb/en/mariadb-connector-j-identity-plugins/) that signs RDS auth tokens
using whatever `AwsCredentialsProvider` the JVM resolves by default (environment, instance profile, pod identity,
etc). That works well when the identity running the application is *also* the identity that should be allowed to
connect to the database. It does not work when you want the database connection to use a **different**, more
narrowly-scoped role — for example, assuming a dedicated `db-operator` role from a shared application identity,
so that database access can be granted/revoked/audited independently of the application's own permissions.

`iam-maria-now` fills that gap: it assumes a configured IAM role via STS, then uses the resulting temporary
credentials to generate the RDS auth token, caching the token for 10 minutes (tokens are valid for 15).

## Origin

This plugin is based on `mariadb-java-client`'s own built-in identity plugin, but with an STS `AssumeRole` step.

## Why

- No long-lived database passwords to rotate, store, or leak.
- The database-connecting identity (an assumed role) can be scoped and audited independently of the
  application's own IAM identity.
- Works anywhere the AWS SDK's default credential chain works (EKS Pod Identity, IRSA, EC2 instance profiles,
  local credentials, etc) as the *base* identity used to assume the target role.

## Installation

```xml
<dependency>
    <groupId>io.github.joshuaclayton</groupId>
    <artifactId>iam-maria-now</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.mariadb.jdbc</groupId>
    <artifactId>mariadb-java-client</artifactId>
    <version>3.5.8</version>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>rds</artifactId>
    <version>2.25.0</version>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sts</artifactId>
    <version>2.25.0</version>
</dependency>
```

The plugin registers itself via Java's `ServiceLoader` mechanism (`META-INF/services`), so no additional
driver configuration is required beyond adding it to the classpath.

## Usage

Add `credentialType=AWS-IAM-ASSUME-ROLE` plus the connection properties below to your JDBC URL:

| Property          | Required | Description                                                                 |
|-------------------|----------|------------------------------------------------------------------------------|
| `assumeRoleArn`    | yes      | ARN of the IAM role to assume before generating the RDS auth token.         |
| `region`           | no       | AWS region of the database. Defaults to the SDK's default region provider.  |
| `roleSessionName`  | no       | STS session name used for the assumed role. Defaults to `iam-maria-now`.    |
| `stsEndpointOverride` | no    | Override the STS endpoint (e.g. a private VPC interface endpoint, or a test double such as LocalStack). |

```
jdbc:mariadb://my-aurora-cluster.cluster-xxxxx.us-west-2.rds.amazonaws.com:3306/mydb
    ?credentialType=AWS-IAM-ASSUME-ROLE
    &assumeRoleArn=arn:aws:iam::123456789012:role/my-db-operator
    &region=us-west-2
    &sslMode=VERIFY_FULL
    &disablePipeline=true
```

The database user (from the JDBC URL or `user` connection property) must have `rds_iam` authentication enabled,
and the assumed role must be granted `rds-db:connect` for that user in the target account.

`mustUseSsl()` returns `true`, so connections must be configured to use TLS (`sslMode` other than `DISABLE`),
matching the requirement AWS imposes on IAM database authentication.

## Testing

```
mvn test               # unit tests only — fast, no Docker required
mvn verify -Pintegration  # adds a LocalStack-backed integration test (requires Docker)
```

- Unit tests cover connection-property validation, plugin caching behaviour, and RDS auth token
  generation. The token-generation tests call the real AWS SDK signing code directly with static
  test credentials — `RdsUtilities.generateAuthenticationToken` only performs local SigV4 presigning
  (no network call), so no mocking is needed there at all.
- The one piece that is a genuine network call — `sts:AssumeRole` — is tested against
  [LocalStack](https://www.localstack.cloud/), a real open-source AWS emulator, via Testcontainers,
  rather than a hand-written mock. This is wired through the `stsEndpointOverride` connection
  property, which also has a legitimate production use: pointing at a private STS VPC endpoint.

## Releasing

CI (`.github/workflows/build.yml`) only runs tests — it never has access to the signing key or
Central credentials. Releases are signed and published locally, intentionally, to keep the private
key off GitHub entirely:

```
mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false   # drop -SNAPSHOT
git commit -am "Release X.Y.Z"
git tag vX.Y.Z

mvn -P release deploy      # signs with the local GPG key, uploads to Central for validation
                            # requires ~/.m2/settings.xml with a <server id="central"> user token

mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT -DgenerateBackupPoms=false   # resume development
git commit -am "Prepare next development iteration"

git push && git push --tags
```

A pushed tag also gives [JitPack](https://jitpack.io/) a resolvable version
(`com.github.joshua-clayton:iam-maria-now:vX.Y.Z`) with no further setup required.

## License

LGPL-2.1-or-later. See [LICENSE](./LICENSE). This project implements the public `CredentialPlugin` /
`CredentialGenerator` SPI of `mariadb-java-client` (itself LGPL-2.1-or-later) without copying its source.
