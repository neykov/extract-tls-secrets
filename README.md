# extract-ssl-secrets

Extracts the shared master key used in secure connections (SSL & TLS)
for use with Wireshark. Works with connections established with the
(Java provided) javax.net.ssl.SSLSocket API.

To extract the keys from a java application use the jvm option 
`-javaagent:<path to jar>/extract-ssl-secter-0.0.1-SNAPSHOT.jar[=<path to secrets log file>]`.
The name of the jar must remain the same - it won't work if renamed.
By default the keys are logged to `ssl-master-secrets.txt`, to
log to a different file specify it after the equals sign. For example:

```
java -javaagent:extract-ssl-secrets-0.0.1-SNAPSHOT.jar=/tmp/secrets.log -jar MyApp.jar
```

To use the file in Wireshark configure the secrets key in
`Edit > Preferences > Protocols > SSL > (Pre)-Master-Secret log filename`
or start with:

```
wireshark -o ssl.keylog_file:/tmp/secrets.log
```

The packets will be decrypted in real-time.

## Building

```
git clone https://github.com/neykov/extract-ssl-secrets.git
cd extract-ssl-secrets
mvn clean package
```
