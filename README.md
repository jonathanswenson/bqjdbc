# starschema-bigquery-jdbc

![Maven Central](https://img.shields.io/maven-central/v/com.github.jonathanswenson/bqjdbc)

This is a [JDBC Driver](https://docs.oracle.com/javase/8/docs/technotes/guides/jdbc/) for [BigQuery](https://cloud.google.com/bigquery)
forked from https://code.google.com/p/starschema-bigquery-jdbc/

You can create a JDBC connection easily for a variety of authentication types.
For instance for a _service account_ in a properties file:

```ini
projectid=disco-parsec-659
type=service
user=abc123e@developer.gserviceaccount.com
password=bigquery_credentials.p12
```

```java
import net.starschema.clouddb.jdbc.*;
import java.sql.*;

public static class Example {
    public static void main(String[] args) {
        // load the class so that it registers itself
        Class.forName("net.starschema.clouddb.jdbc.BQDriver");
        final String jdbcUrl = BQSupportFuncts.constructUrlFromPropertiesFile(
                                    BQSupportFuncts.readFromPropFile(
                                        getClass().getResource("/serviceaccount.properties").getFile()
                                    ));
        final Connection con = DriverManager.getConnection(jdbcUrl);
        // perform SQL against BigQuery now!
    }
}
```

The dependency is provided at the following coordinates:
```xml
<dependency>
    <groupId>com.github.jonathanswenson</groupId>
    <artifactId>bqjdbc</artifactId>
    <version>...</version>
</dependency>
```

A fat (shaded) jar is provided at the following coordinates.
```xml
<dependency>
    <groupId>com.github.jonathanswenson</groupId>
    <artifactId>bqjdbc</artifactId>
    <version>...</version>
    <classifier>shaded</classifier>
</dependency>
```

## Development

### Releases

Releases are handled through GitHub actions, and kicked off when a release is created.

> 💡 Make sure that  `-SNAPSHOT` is not part of the version when you create a release.

1. Prepare a release by removing `-SNAPSHOT` from the version in _pom.xml_

2. Initiate a release and be sure to write a meaningful description.

    > This will also create a tag with the specified name

    ![Creating a release](./create_release.png)

3. Check the GitHub action to see that it was a success

    ![Verify action is successful](./github_action_success.png)

4. Create a new commit by bumping the version and adding `-SNAPSHOT` to it