package io.tieringkv.command;

/** INCRBY key increment：原子增量（ADR-0269）。 */
public final class IncrByCommand
        extends IntegerArithmeticCommand {

    public IncrByCommand() {
        super("incrby", 2);
    }

    @Override
    long fixedDelta() {
        return 1;
    }
}
