package extractor;

import java.lang.instrument.Instrumentation;

public class Main {
	private static final String DEFAULT_SECRETS_FILE = "ssl-master-secrets.txt";

	public static void premain(String agentArgs, Instrumentation inst) {
		String secretsFile;
		if (agentArgs != null && !agentArgs.isEmpty()) {
			secretsFile = agentArgs;
		} else {
			secretsFile = DEFAULT_SECRETS_FILE;
		}
		MasterSecretCallback.setSecretsFileName(secretsFile);
		inst.addTransformer(new Transformer());
	}
	
	public static void agentmain(String agentArgs, Instrumentation inst) {
		
	}
}
