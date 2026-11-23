package io.tieringkv.command;

/** DECR key：原子自减（ADR-0269）。 */
public final class DecrCommand
        extends IntegerArithmeticCommand {

    public DecrCommand() {
        super("decr", 1);
    }

    @Override
    long fixedDelta() {
        return -1;
    }
}
