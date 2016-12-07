# extract-ssl-secrets

Extracts the shared master key used in secure connections (SSL & TLS)
for use with Wireshark. Works with connections established with the
(Java provided) javax.net.ssl.SSLSocket API.

To extract the keys from a java application use the jvm option 
`-javaagent:<path to jar>/extract-ssl-secrets-1.0.0-SNAPSHOT.jar[=<path to secrets log file>]`.
The name of the jar must remain the same - it won't work if renamed.
By default the keys are logged to `ssl-master-secrets.txt`, to
log to a different file specify it after the equals sign. For example:

```
java -javaagent:extract-ssl-secrets-1.0.0-SNAPSHOT.jar=/tmp/secrets.log -jar MyApp.jar
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
