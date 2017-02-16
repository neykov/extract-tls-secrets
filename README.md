# extract-ssl-secrets

Decrypt SSL/TLS connections in real time.

Extracts the shared master key used in secure connections (SSL & TLS)
for use with Wireshark. Works with connections established with the
(Java provided) javax.net.ssl.SSLSocket API.

## Download

Download [extract-ssl-secrets-1.0.0.jar](https://repo1.maven.org/maven2/name/neykov/extract-ssl-secrets/1.0.0/extract-ssl-secrets-1.0.0.jar).

## Usage

Attaching to an existing Java process to extract the keys. Requires a JDK
install with `JAVA_HOME` (for Windows) or `java.home` (for Unix) pointing
to it. First list the available process IDs with:

```
java -jar extract-ssl-secrets-1.1.0-SNAPSHOT.jar list
```

Next attach to the process by executing:

```
java -jar extract-ssl-secrets-1.1.0-SNAPSHOT.jar <pid> [<path to secrets log file>]
```

If no JDK is installed it's still possible to attach to a Java process. 
Use the JVM startup option 
`-javaagent:<path to jar>/extract-ssl-secrets-1.0.0.jar[=<path to secrets log file>]`.

For example:

```
java -javaagent:extract-ssl-secrets-1.0.0.jar=/tmp/secrets.log -jar MyApp.jar
```

By default the keys are logged to `ssl-master-secrets.txt` in the target
process working folder. To log to a different file specify the optional
`<path to secrets log file>` in one of the above commands.

To use the file in Wireshark configure the secrets file in
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

## Troubleshooting

If you get an empty window after selecting "Follow/SSL Stream" from the context menu
or are not seeing HTTP protocol packets in the packet list then you can fix this by either:
  * Save the capture as a file and open it again
  * In the Wireshark settings in "Procotols/SSL" toggle "Reassemble SSL Application Data spanning multiple SSL records".
  The exact state of the checkbox doesn't matter, but it will force a reload which will force proper decryption of the packets.

The bug seems to be related to the UI side of wireshark as the SSL debug logs show the message successfully being decrypted.

Reports of the problem:
  * https://ask.wireshark.org/questions/33879/ssl-decrypt-shows-ok-in-ssl-debug-file-but-not-in-wireshark
  * https://bugs.wireshark.org/bugzilla/show_bug.cgi?id=9154


If "Follow/SSL Stream" is not enabled the server is probably on a non-standard port so Wireshark can't infer that the packets contain SSL traffic. To hint it that it should be decoding the packets as SSL right click on any of the packets to open the context menu, select "Decode As" and add the server port, select "SSL" protocol in the "Current" column. If it's still not able to decrypt try the same by saving the capture in a file and re-opening it.
