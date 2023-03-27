# extract-tls-secrets

Decrypt HTTPS/TLS connections on-the-fly. Extract the shared secrets from 
secure TLS connections for use with [Wireshark](https://www.wireshark.org/).
Attach to a Java process on either side of the connection to start decrypting.

## Usage

Download from [extract-tls-secrets-4.0.0.jar](https://repo1.maven.org/maven2/name/neykov/extract-tls-secrets/4.0.0/extract-tls-secrets-4.0.0.jar).
Then attach to a Java process in one of two ways:

### Attach on startup 

Add a startup argument to the JVM options: `-javaagent:<path to jar>/extract-tls-secrets-4.0.0.jar=<path to secrets log file>`

For example to launch an application from a jar file run:

```shell script
java -javaagent:~/Downloads/extract-tls-secrets-4.0.0.jar=/tmp/secrets.log -jar MyApp.jar
```

To launch in Tomcat add the parameter to `CATALINA_OPTS`:

```shell script
CATALINA_OPTS=-javaagent:~/Downloads/extract-tls-secrets-4.0.0.jar=/tmp/secrets.log bin/catalina.sh run
```

### Attach to a running process

Attaching to an existing Java process requires a JDK install with `JAVA_HOME` 
pointing to it.

To list the available process IDs run:

```
java -jar ~/Downloads/extract-tls-secrets-4.0.0.jar list
```

Next attach to the process by executing:

```
java -jar ~/Downloads/extract-tls-secrets-4.0.0.jar <pid> /tmp/secrets.log
```

### Decrypt the capture in Wireshark

To decrypt the capture you need to let Wireshark know where the secrets file is. 
Configure the path in
`Preferences > Protocols > TLS (SSL for older versions) > (Pre)-Master-Secret log filename`.

Alternatively start Wireshark with:

```
wireshark -o tls.keylog_file:/tmp/secrets.log
```

The packets will be decrypted in real-time.

For a step by step tutorial of using the secrets log file (SSLKEYLOGFILE as referenced usually)
refer to the Peter Wu's [Debugging TLS issues with Wireshark](https://lekensteyn.nl/files/wireshark-tls-debugging-sharkfest19eu.pdf)
presentation. Even more information can be found at the [Wireshark TLS](https://wiki.wireshark.org/TLS) page. 

## Requirements

Requires at least Oracle/OpenJDK Java 6. Does not support IBM Java and custom 
security providers like Bouncy Castle, Conscrypt.

## Building

```
git clone https://github.com/neykov/extract-tls-secrets.git
cd extract-tls-secrets
mvn clean package
```

Running the integration tests requires Docker to be installed on the system:

```shell script
mvn verify
```

## Troubleshooting

If you get an empty window after selecting "Follow/TLS Stream" from the context menu
or are not seeing HTTP protocol packets in the packet list then you can fix this by either:
  * Save the capture as a file and open it again
  * In the Wireshark settings in "Procotols/TLS" toggle "Reassemble TLS Application Data spanning multiple SSL records".
  The exact state of the checkbox doesn't matter, but it will force a reload which will force proper decryption of the packets.

The bug seems to be related to the UI side of wireshark as the TLS debug logs show the message successfully being decrypted.

Reports of the problem:
  * https://ask.wireshark.org/questions/33879/ssl-decrypt-shows-ok-in-ssl-debug-file-but-not-in-wireshark
  * https://bugs.wireshark.org/bugzilla/show_bug.cgi?id=9154


If "Follow/TLS Stream" is not enabled the server is probably on a non-standard port so Wireshark can't infer that the 
packets contain TLS traffic. To hint it that it should be decoding the packets as TLS 
right click on any of the packets to open the context menu, select "Decode As" and add 
the server port, select "TLS" protocol in the "Current" column. If it's still not able 
to decrypt try the same by saving the capture in a file and re-opening it.
