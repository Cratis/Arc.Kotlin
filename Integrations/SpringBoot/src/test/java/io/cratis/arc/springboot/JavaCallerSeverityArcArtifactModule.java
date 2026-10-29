// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.artifacts.AllowCallerValidationSeverity;
import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandHandler;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.results.ValidationResultSeverity;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.coroutines.Continuation;

/** Java command fixtures registered only in caller severity hosting test contexts. */
public final class JavaCallerSeverityArcArtifactModule extends ArcArtifactModule {
    public static final Map<Class<?>, AtomicInteger> INVOCATIONS = Map.of(
        PlainSeverityCommand.class, new AtomicInteger(),
        OptedInSeverityCommand.class, new AtomicInteger(),
        StricterSeverityCommand.class, new AtomicInteger(),
        WarningsAsErrorsSeverityCommand.class, new AtomicInteger());

    public JavaCallerSeverityArcArtifactModule() {
        super(List.of(
            new SeverityCommandHandler(PlainSeverityCommand.class, false),
            new SeverityCommandHandler(OptedInSeverityCommand.class, false),
            new SeverityCommandHandler(StricterSeverityCommand.class, false),
            new SeverityCommandHandler(WarningsAsErrorsSeverityCommand.class, true)), List.of());
    }

    public record PlainSeverityCommand(String value) { }

    @AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Error)
    public record OptedInSeverityCommand(String value) { }

    @AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Information)
    public record StricterSeverityCommand(String value) { }

    public record WarningsAsErrorsSeverityCommand(String value) { }

    private static final class SeverityCommandHandler implements CommandHandler {
        private final Class<?> commandType;
        private final CommandDescriptor metadata;

        private SeverityCommandHandler(Class<?> commandType, boolean treatWarningsAsErrors) {
            this.commandType = commandType;
            metadata = new CommandDescriptor(
                commandType.getSimpleName(), commandType.getName(), List.of(), new RouteOptions(),
                List.of("fixtures"), new AuthorizationMetadata(true, null, List.of(), List.of()),
                null, treatWarningsAsErrors);
        }

        @Override
        public Class<?> getCommandType() {
            return commandType;
        }

        @Override
        public CommandDescriptor getMetadata() {
            return metadata;
        }

        @Override
        public boolean getAllowsAnonymous() {
            return true;
        }

        @Override
        public Object invoke(CommandContext context, Continuation<? super Object> continuation) {
            INVOCATIONS.get(commandType).incrementAndGet();
            return null;
        }
    }
}
