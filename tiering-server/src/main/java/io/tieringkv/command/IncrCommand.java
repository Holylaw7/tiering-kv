package io.tieringkv.command;

/** INCR key：原子自增（ADR-0269）。 */
public final class IncrCommand
        extends IntegerArithmeticCommand {

    public IncrCommand() {
        super("incr", 1);
    }

    @Override
    long fixedDelta() {
        return 1;
    }
}
