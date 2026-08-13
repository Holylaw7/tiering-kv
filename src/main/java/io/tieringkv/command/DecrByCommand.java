package io.tieringkv.command;

/** DECRBY key decrement：原子减量（ADR-0269）。 */
public final class DecrByCommand
        extends IntegerArithmeticCommand {

    public DecrByCommand() {
        super("decrby", 2);
    }

    @Override
    long fixedDelta() {
        return -1;
    }
}
