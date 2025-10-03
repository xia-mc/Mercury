package asia.lira.mercury.impl;

public interface IInterpreter<S> {
    int execute(S source, String command, String[] commands);
}
