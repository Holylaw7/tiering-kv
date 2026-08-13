package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;

/** EXEC：顺序执行队列命令并返回结果数组（ADR-0287）。 */
public final class ExecCommand implements Command {

    private final CommandRegistry registry;

    public ExecCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        ConnectionContext context = ConnectionContext.current();
        if (context == null || !context.inMulti()) {
            return new RespError("ERR EXEC without MULTI");
        }
        List<RespCommand> queue = context.txnQueue();
        context.clearTxnQueue();
        CommandEngine engine = new CommandEngine(registry, storage);
        List<RespValue> results = new ArrayList<>();
        for (RespCommand command : queue) {
            results.add(engine.execute(command));
        }
        return new RespArray(results);
    }
}
