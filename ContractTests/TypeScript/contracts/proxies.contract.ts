// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { Globals } from "@cratis/arc";
import type { Command } from "@cratis/arc/commands";
import { DateOnly, Guid, TimeOnly } from "@cratis/fundamentals";
import type {
    ChangeSet,
    IObservableQueryFor,
    ObservableQuerySubscription,
    QueryResultWithState,
} from "@cratis/arc/queries";
import type {
    ObservableQueryWhen,
    PerformQuery,
    SetPage,
    SetPageSize,
    SetSorting,
} from "@cratis/arc.react/queries";
import * as proxies from "../generated";
import {
    CreateTaskBatch as RuntimeCreateTaskBatch,
    type ICreateTaskBatch as RuntimeCreateTaskBatchContent,
    TaskCreated as RuntimeTaskCreated,
} from "../generated/runtime";
import {
    type AggregateClientResponse,
    All,
    type AllParameters,
    ById,
    type ByIdParameters,
    AnnotatedFixtureState,
    ConceptTemporalReadModel,
    ContextualKotlin,
    type ContextualKotlinParameters,
    CyclicFixture,
    Defaulted,
    type DefaultedParameters,
    EventCommand,
    ExplicitFixtureState,
    Filtered,
    FindConceptTemporal,
    type FindConceptTemporalParameters,
    FindJavaTemporal,
    type FindJavaTemporalParameters,
    FindKotlinTemporal,
    type FindKotlinTemporalParameters,
    FixtureCircle,
    FixtureFilter,
    FixturePermissions,
    FixtureRectangle,
    FixtureResponse,
    type FixtureShape,
    FixtureState,
    type JavaAggregateClientResponse,
    JavaAnnotatedFixtureState,
    JavaAsyncCommand,
    JavaMapMetadataCommand,
    type IJavaMapMetadataCommand,
    type JavaMapReadModel,
    type IJavaTemporalCommand,
    type JavaFixtureContract,
    JavaFixturePermissions,
    JavaFixtureState,
    JavaQueryReadModel,
    JavaTemporalCommand,
    JavaTemporalReadModel,
    JavaTemporalResult,
    ObserveDefaulted,
    type ObserveDefaultedParameters,
    ObserveJava,
    type IKotlinTemporalCommand,
    KotlinHandledOnlyResponseCommand,
    KotlinNestedResponseCommand,
    KotlinPairResponseCommand,
    KotlinMapMetadataCommand,
    type IKotlinMapMetadataCommand,
    type KotlinMapReadModel,
    KotlinQueryReadModel,
    ObserveAll,
    type ObserveAllParameters,
    KotlinRegularCommand,
    KotlinSuspendCommand,
    KotlinTemporalCommand,
    KotlinTemporalReadModel,
    KotlinTemporalResult,
    JavaPairResponseCommand,
    type IMetadataCommand,
    MetadataCommand,
    ObserveSingle,
    Optional,
    type OptionalParameters,
    Page,
    Single,
    type SpringDataDirect,
    type SpringDataDirectParameters,
    allFixturePermissions,
    allJavaFixturePermissions,
} from "../generated";

void proxies;

const kotlinCommand = new KotlinRegularCommand();
kotlinCommand.commandId = "kotlin";
kotlinCommand.message = "hello";
kotlinCommand.optionalLabel = undefined;

const javaCommand = new JavaAsyncCommand();
javaCommand.commandId = "java";
javaCommand.value = "hello";

const metadataCommand = new MetadataCommand();
metadataCommand.commandId = Guid.parse("00000000-0000-0000-0000-000000000000");
metadataCommand.states = [FixtureState.active];
metadataCommand.phone = "+1 (555) 010-0200";
metadataCommand.website = "https://cratis.io";
metadataCommand.creditCard = "4111-1111-1111-1111";
metadataCommand.customerName = "KOTLIN";
metadataCommand.optionalCustomerName = undefined;
metadataCommand.customerNames = ["FIRST", "SECOND"];
metadataCommand.quantity = 1;
metadataCommand.orderId = Guid.parse("11111111-1111-1111-1111-111111111111");
metadataCommand.deliveryDate = DateOnly.from(2026, 8, 30);
metadataCommand.deliveryTime = TimeOnly.from(12, 30, 0);
metadataCommand.stateCode = FixtureState.active;
metadataCommand.javaCode = "JAVA";
metadataCommand.javaQuantity = 2;
metadataCommand.javaOrderId = Guid.parse(
    "22222222-2222-2222-2222-222222222222",
);
metadataCommand.javaDeliveryDate = DateOnly.from(2026, 8, 31);
metadataCommand.javaDeliveryTime = TimeOnly.from(13, 45, 0);
metadataCommand.javaStateCode = JavaFixtureState.READY;

const kotlinTemporalIdentifier = Guid.parse(
    "33333333-3333-3333-3333-333333333333",
);
const kotlinTemporalDate = DateOnly.from(2026, 9, 1);
const kotlinTemporalTime = TimeOnly.from(14, 15, 16);
const javaTemporalIdentifier = Guid.parse(
    "44444444-4444-4444-4444-444444444444",
);
const javaTemporalDate = DateOnly.from(2026, 9, 2);
const javaTemporalTime = TimeOnly.from(15, 16, 17);

const kotlinTemporalCommandContent: IKotlinTemporalCommand = {
    identifier: kotlinTemporalIdentifier,
    date: kotlinTemporalDate,
    time: kotlinTemporalTime,
};
const kotlinTemporalCommand = new KotlinTemporalCommand();
kotlinTemporalCommand.identifier = kotlinTemporalIdentifier;
kotlinTemporalCommand.date = kotlinTemporalDate;
kotlinTemporalCommand.time = kotlinTemporalTime;
const javaTemporalCommandContent: IJavaTemporalCommand = {
    identifier: javaTemporalIdentifier,
    date: javaTemporalDate,
    time: javaTemporalTime,
};
const javaTemporalCommand = new JavaTemporalCommand();
javaTemporalCommand.identifier = javaTemporalIdentifier;
javaTemporalCommand.date = javaTemporalDate;
javaTemporalCommand.time = javaTemporalTime;

const kotlinTemporalResult = new KotlinTemporalResult();
kotlinTemporalResult.identifier = kotlinTemporalIdentifier;
kotlinTemporalResult.date = kotlinTemporalDate;
kotlinTemporalResult.time = kotlinTemporalTime;
const javaTemporalResult = new JavaTemporalResult();
javaTemporalResult.identifier = javaTemporalIdentifier;
javaTemporalResult.date = javaTemporalDate;
javaTemporalResult.time = javaTemporalTime;

const kotlinTemporalModel = new KotlinTemporalReadModel();
kotlinTemporalModel.identifier = kotlinTemporalIdentifier;
kotlinTemporalModel.date = kotlinTemporalDate;
kotlinTemporalModel.time = kotlinTemporalTime;
const javaTemporalModel = new JavaTemporalReadModel();
javaTemporalModel.identifier = javaTemporalIdentifier;
javaTemporalModel.date = javaTemporalDate;
javaTemporalModel.time = javaTemporalTime;

const findKotlinTemporalParameters: FindKotlinTemporalParameters = {
    identifier: kotlinTemporalIdentifier,
    date: kotlinTemporalDate,
    time: kotlinTemporalTime,
};
const findKotlinTemporal = new FindKotlinTemporal();
findKotlinTemporal.identifier = findKotlinTemporalParameters.identifier;
findKotlinTemporal.date = findKotlinTemporalParameters.date;
findKotlinTemporal.time = findKotlinTemporalParameters.time;
const findJavaTemporalParameters: FindJavaTemporalParameters = {
    identifier: javaTemporalIdentifier,
    date: javaTemporalDate,
    time: javaTemporalTime,
};
const findJavaTemporal = new FindJavaTemporal();
findJavaTemporal.identifier = findJavaTemporalParameters.identifier;
findJavaTemporal.date = findJavaTemporalParameters.date;
findJavaTemporal.time = findJavaTemporalParameters.time;

const conceptTemporalModel = new ConceptTemporalReadModel();
conceptTemporalModel.identifier = metadataCommand.orderId;
conceptTemporalModel.date = metadataCommand.deliveryDate;
conceptTemporalModel.time = metadataCommand.deliveryTime;
conceptTemporalModel.javaIdentifier = metadataCommand.javaOrderId;
conceptTemporalModel.javaDate = metadataCommand.javaDeliveryDate;
conceptTemporalModel.javaTime = metadataCommand.javaDeliveryTime;
const findConceptTemporalParameters: FindConceptTemporalParameters = {
    identifier: metadataCommand.orderId,
    date: metadataCommand.deliveryDate,
    time: metadataCommand.deliveryTime,
    javaIdentifier: metadataCommand.javaOrderId,
    javaDate: metadataCommand.javaDeliveryDate,
    javaTime: metadataCommand.javaDeliveryTime,
};
const findConceptTemporal = new FindConceptTemporal();
findConceptTemporal.identifier = findConceptTemporalParameters.identifier;
findConceptTemporal.date = findConceptTemporalParameters.date;
findConceptTemporal.time = findConceptTemporalParameters.time;
findConceptTemporal.javaIdentifier =
    findConceptTemporalParameters.javaIdentifier;
findConceptTemporal.javaDate = findConceptTemporalParameters.javaDate;
findConceptTemporal.javaTime = findConceptTemporalParameters.javaTime;

const eventCommand = new EventCommand();
eventCommand.commandId = "event";

const kotlinMapCommand = new KotlinMapMetadataCommand();
kotlinMapCommand.strings = { language: "kotlin" };
kotlinMapCommand.numbers = { values: [1, 2] };
kotlinMapCommand.nested = { flags: { ready: true } };
kotlinMapCommand.optional = undefined;
const javaMapCommand = new JavaMapMetadataCommand();
javaMapCommand.strings = {};
javaMapCommand.numbers = { values: [3, 4] };
javaMapCommand.nested = { flags: { ready: true } };
javaMapCommand.optional = undefined;

type IsAny<T> = 0 extends 1 & T ? true : false;
type IsUnknown<T> =
    IsAny<T> extends true ? false : unknown extends T ? true : false;
type IsExact<Actual, Expected> =
    IsAny<Actual> extends true
        ? false
        : IsUnknown<Actual> extends true
          ? false
          : IsAny<Expected> extends true
            ? false
            : IsUnknown<Expected> extends true
              ? false
              : (<T>() => T extends Actual ? 1 : 2) extends <
                      T,
                  >() => T extends Expected ? 1 : 2
                ? (<T>() => T extends Expected ? 1 : 2) extends <
                      T,
                  >() => T extends Actual ? 1 : 2
                    ? true
                    : false
                : false;
type Assert<T extends true> = T;
type IsAssignable<Source, Target> = [Source] extends [Target] ? true : false;
type CommandResponse<TCommand> =
    TCommand extends Command<infer _TCommandContent, infer TResponse>
        ? TResponse
        : never;
type CommandGenerics<TCommand> =
    TCommand extends Command<infer TCommandContent, infer TResponse>
        ? [TCommandContent, TResponse]
        : never;

type RecursiveMapCommandContract = Assert<
    IsExact<
        [
            NonNullable<IKotlinMapMetadataCommand["strings"]>,
            NonNullable<IKotlinMapMetadataCommand["numbers"]>,
            NonNullable<IKotlinMapMetadataCommand["nested"]>,
            KotlinMapMetadataCommand["optional"],
            NonNullable<IJavaMapMetadataCommand["strings"]>,
            NonNullable<IJavaMapMetadataCommand["numbers"]>,
            NonNullable<IJavaMapMetadataCommand["nested"]>,
            JavaMapMetadataCommand["optional"],
        ],
        [
            Record<string, string>,
            Record<string, number[]>,
            Record<string, Record<string, boolean>>,
            Record<string, string> | undefined,
            Record<string, string>,
            Record<string, number[]>,
            Record<string, Record<string, boolean>>,
            Record<string, string> | undefined,
        ]
    >
>;
type RecursiveMapReadModelContract = Assert<
    IsExact<
        [
            KotlinMapReadModel["strings"],
            KotlinMapReadModel["numbers"],
            KotlinMapReadModel["nested"],
            KotlinMapReadModel["optional"],
            JavaMapReadModel["strings"],
            JavaMapReadModel["numbers"],
            JavaMapReadModel["nested"],
            JavaMapReadModel["optional"],
        ],
        [
            Record<string, string>,
            Record<string, number[]>,
            Record<string, Record<string, boolean>>,
            Record<string, string> | undefined,
            Record<string, string>,
            Record<string, number[]>,
            Record<string, Record<string, boolean>>,
            Record<string, string> | undefined,
        ]
    >
>;
type RecursiveMapRejectsLegacyRepresentations = Assert<
    IsExact<
        [
            IsAssignable<string[], KotlinMapMetadataCommand["strings"]>,
            IsAssignable<
                Array<[string, string]>,
                KotlinMapMetadataCommand["strings"]
            >,
            IsAssignable<
                { _entries: Array<[string, string]> },
                KotlinMapMetadataCommand["strings"]
            >,
            IsAssignable<
                Record<string, string>,
                KotlinMapMetadataCommand["numbers"]
            >,
            IsAssignable<
                Record<string, boolean>,
                KotlinMapMetadataCommand["nested"]
            >,
            IsAssignable<Array<[string, string]>, JavaMapReadModel["strings"]>,
        ],
        [false, false, false, false, false, false]
    >
>;

const recursiveMapContracts: readonly [
    RecursiveMapCommandContract,
    RecursiveMapReadModelContract,
    RecursiveMapRejectsLegacyRepresentations,
] = [true, true, true];

type DirectTemporalCommandContract = Assert<
    IsExact<
        [
            KotlinTemporalCommand["identifier"],
            KotlinTemporalCommand["date"],
            KotlinTemporalCommand["time"],
            JavaTemporalCommand["identifier"],
            JavaTemporalCommand["date"],
            JavaTemporalCommand["time"],
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type DirectTemporalCommandContentContract = Assert<
    IsExact<
        [
            NonNullable<IKotlinTemporalCommand["identifier"]>,
            NonNullable<IKotlinTemporalCommand["date"]>,
            NonNullable<IKotlinTemporalCommand["time"]>,
            NonNullable<IJavaTemporalCommand["identifier"]>,
            NonNullable<IJavaTemporalCommand["date"]>,
            NonNullable<IJavaTemporalCommand["time"]>,
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type DirectTemporalResultContract = Assert<
    IsExact<
        [
            KotlinTemporalResult["identifier"],
            KotlinTemporalResult["date"],
            KotlinTemporalResult["time"],
            JavaTemporalResult["identifier"],
            JavaTemporalResult["date"],
            JavaTemporalResult["time"],
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type DirectTemporalModelContract = Assert<
    IsExact<
        [
            KotlinTemporalReadModel["identifier"],
            KotlinTemporalReadModel["date"],
            KotlinTemporalReadModel["time"],
            JavaTemporalReadModel["identifier"],
            JavaTemporalReadModel["date"],
            JavaTemporalReadModel["time"],
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type DirectTemporalQueryContract = Assert<
    IsExact<
        [
            FindKotlinTemporalParameters["identifier"],
            FindKotlinTemporalParameters["date"],
            FindKotlinTemporalParameters["time"],
            FindJavaTemporalParameters["identifier"],
            FindJavaTemporalParameters["date"],
            FindJavaTemporalParameters["time"],
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type ConceptTemporalCommandContract = Assert<
    IsExact<
        [
            NonNullable<IMetadataCommand["orderId"]>,
            NonNullable<IMetadataCommand["deliveryDate"]>,
            NonNullable<IMetadataCommand["deliveryTime"]>,
            NonNullable<IMetadataCommand["javaOrderId"]>,
            NonNullable<IMetadataCommand["javaDeliveryDate"]>,
            NonNullable<IMetadataCommand["javaDeliveryTime"]>,
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type ConceptTemporalModelContract = Assert<
    IsExact<
        [
            ConceptTemporalReadModel["identifier"],
            ConceptTemporalReadModel["date"],
            ConceptTemporalReadModel["time"],
            ConceptTemporalReadModel["javaIdentifier"],
            ConceptTemporalReadModel["javaDate"],
            ConceptTemporalReadModel["javaTime"],
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type ConceptTemporalQueryContract = Assert<
    IsExact<
        [
            FindConceptTemporalParameters["identifier"],
            FindConceptTemporalParameters["date"],
            FindConceptTemporalParameters["time"],
            FindConceptTemporalParameters["javaIdentifier"],
            FindConceptTemporalParameters["javaDate"],
            FindConceptTemporalParameters["javaTime"],
        ],
        [Guid, DateOnly, TimeOnly, Guid, DateOnly, TimeOnly]
    >
>;
type DirectTemporalCommandRejectsLegacyValues = Assert<
    IsExact<
        [
            IsAssignable<string, KotlinTemporalCommand["identifier"]>,
            IsAssignable<Date, KotlinTemporalCommand["identifier"]>,
            IsAssignable<string, KotlinTemporalCommand["date"]>,
            IsAssignable<Date, KotlinTemporalCommand["date"]>,
            IsAssignable<string, KotlinTemporalCommand["time"]>,
            IsAssignable<Date, KotlinTemporalCommand["time"]>,
            IsAssignable<string, JavaTemporalCommand["identifier"]>,
            IsAssignable<Date, JavaTemporalCommand["identifier"]>,
            IsAssignable<string, JavaTemporalCommand["date"]>,
            IsAssignable<Date, JavaTemporalCommand["date"]>,
            IsAssignable<string, JavaTemporalCommand["time"]>,
            IsAssignable<Date, JavaTemporalCommand["time"]>,
        ],
        [
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
        ]
    >
>;
type DirectTemporalModelRejectsLegacyValues = Assert<
    IsExact<
        [
            IsAssignable<string, KotlinTemporalReadModel["identifier"]>,
            IsAssignable<Date, KotlinTemporalReadModel["identifier"]>,
            IsAssignable<string, KotlinTemporalReadModel["date"]>,
            IsAssignable<Date, KotlinTemporalReadModel["date"]>,
            IsAssignable<string, KotlinTemporalReadModel["time"]>,
            IsAssignable<Date, KotlinTemporalReadModel["time"]>,
            IsAssignable<string, JavaTemporalReadModel["identifier"]>,
            IsAssignable<Date, JavaTemporalReadModel["identifier"]>,
            IsAssignable<string, JavaTemporalReadModel["date"]>,
            IsAssignable<Date, JavaTemporalReadModel["date"]>,
            IsAssignable<string, JavaTemporalReadModel["time"]>,
            IsAssignable<Date, JavaTemporalReadModel["time"]>,
        ],
        [
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
        ]
    >
>;
type DirectTemporalQueryRejectsLegacyValues = Assert<
    IsExact<
        [
            IsAssignable<string, FindKotlinTemporalParameters["identifier"]>,
            IsAssignable<Date, FindKotlinTemporalParameters["identifier"]>,
            IsAssignable<string, FindKotlinTemporalParameters["date"]>,
            IsAssignable<Date, FindKotlinTemporalParameters["date"]>,
            IsAssignable<string, FindKotlinTemporalParameters["time"]>,
            IsAssignable<Date, FindKotlinTemporalParameters["time"]>,
            IsAssignable<string, FindJavaTemporalParameters["identifier"]>,
            IsAssignable<Date, FindJavaTemporalParameters["identifier"]>,
            IsAssignable<string, FindJavaTemporalParameters["date"]>,
            IsAssignable<Date, FindJavaTemporalParameters["date"]>,
            IsAssignable<string, FindJavaTemporalParameters["time"]>,
            IsAssignable<Date, FindJavaTemporalParameters["time"]>,
        ],
        [
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
        ]
    >
>;
type ConceptTemporalCommandRejectsLegacyValues = Assert<
    IsExact<
        [
            IsAssignable<string, NonNullable<IMetadataCommand["orderId"]>>,
            IsAssignable<Date, NonNullable<IMetadataCommand["orderId"]>>,
            IsAssignable<string, NonNullable<IMetadataCommand["deliveryDate"]>>,
            IsAssignable<Date, NonNullable<IMetadataCommand["deliveryDate"]>>,
            IsAssignable<string, NonNullable<IMetadataCommand["deliveryTime"]>>,
            IsAssignable<Date, NonNullable<IMetadataCommand["deliveryTime"]>>,
            IsAssignable<string, NonNullable<IMetadataCommand["javaOrderId"]>>,
            IsAssignable<Date, NonNullable<IMetadataCommand["javaOrderId"]>>,
            IsAssignable<
                string,
                NonNullable<IMetadataCommand["javaDeliveryDate"]>
            >,
            IsAssignable<
                Date,
                NonNullable<IMetadataCommand["javaDeliveryDate"]>
            >,
            IsAssignable<
                string,
                NonNullable<IMetadataCommand["javaDeliveryTime"]>
            >,
            IsAssignable<
                Date,
                NonNullable<IMetadataCommand["javaDeliveryTime"]>
            >,
        ],
        [
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
        ]
    >
>;
type ConceptTemporalModelRejectsLegacyValues = Assert<
    IsExact<
        [
            IsAssignable<string, ConceptTemporalReadModel["identifier"]>,
            IsAssignable<Date, ConceptTemporalReadModel["identifier"]>,
            IsAssignable<string, ConceptTemporalReadModel["date"]>,
            IsAssignable<Date, ConceptTemporalReadModel["date"]>,
            IsAssignable<string, ConceptTemporalReadModel["time"]>,
            IsAssignable<Date, ConceptTemporalReadModel["time"]>,
            IsAssignable<string, ConceptTemporalReadModel["javaIdentifier"]>,
            IsAssignable<Date, ConceptTemporalReadModel["javaIdentifier"]>,
            IsAssignable<string, ConceptTemporalReadModel["javaDate"]>,
            IsAssignable<Date, ConceptTemporalReadModel["javaDate"]>,
            IsAssignable<string, ConceptTemporalReadModel["javaTime"]>,
            IsAssignable<Date, ConceptTemporalReadModel["javaTime"]>,
        ],
        [
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
        ]
    >
>;
type ConceptTemporalQueryRejectsLegacyValues = Assert<
    IsExact<
        [
            IsAssignable<string, FindConceptTemporalParameters["identifier"]>,
            IsAssignable<Date, FindConceptTemporalParameters["identifier"]>,
            IsAssignable<string, FindConceptTemporalParameters["date"]>,
            IsAssignable<Date, FindConceptTemporalParameters["date"]>,
            IsAssignable<string, FindConceptTemporalParameters["time"]>,
            IsAssignable<Date, FindConceptTemporalParameters["time"]>,
            IsAssignable<
                string,
                FindConceptTemporalParameters["javaIdentifier"]
            >,
            IsAssignable<Date, FindConceptTemporalParameters["javaIdentifier"]>,
            IsAssignable<string, FindConceptTemporalParameters["javaDate"]>,
            IsAssignable<Date, FindConceptTemporalParameters["javaDate"]>,
            IsAssignable<string, FindConceptTemporalParameters["javaTime"]>,
            IsAssignable<Date, FindConceptTemporalParameters["javaTime"]>,
        ],
        [
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
        ]
    >
>;
type KotlinTemporalResponseContract = Assert<
    IsExact<CommandResponse<KotlinTemporalCommand>, KotlinTemporalResult>
>;
type JavaTemporalResponseContract = Assert<
    IsExact<CommandResponse<JavaTemporalCommand>, JavaTemporalResult>
>;
const temporalTypeContracts: readonly [
    DirectTemporalCommandContract,
    DirectTemporalCommandContentContract,
    DirectTemporalResultContract,
    DirectTemporalModelContract,
    DirectTemporalQueryContract,
    ConceptTemporalCommandContract,
    ConceptTemporalModelContract,
    ConceptTemporalQueryContract,
    DirectTemporalCommandRejectsLegacyValues,
    DirectTemporalModelRejectsLegacyValues,
    DirectTemporalQueryRejectsLegacyValues,
    ConceptTemporalCommandRejectsLegacyValues,
    ConceptTemporalModelRejectsLegacyValues,
    ConceptTemporalQueryRejectsLegacyValues,
    KotlinTemporalResponseContract,
    JavaTemporalResponseContract,
] = [
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
    true,
];

type KotlinPairResponseContract = Assert<
    IsExact<CommandResponse<KotlinPairResponseCommand>, AggregateClientResponse>
>;
type JavaPairResponseContract = Assert<
    IsExact<
        CommandResponse<JavaPairResponseCommand>,
        JavaAggregateClientResponse
    >
>;
type KotlinNestedResponseContract = Assert<
    IsExact<
        CommandResponse<KotlinNestedResponseCommand>,
        AggregateClientResponse[]
    >
>;
type KotlinHandledOnlyResponseContract = Assert<
    IsExact<CommandResponse<KotlinHandledOnlyResponseCommand>, object>
>;
type RuntimeBatchResponseContract = Assert<
    IsExact<CommandResponse<RuntimeCreateTaskBatch>, RuntimeTaskCreated[]>
>;
type RuntimeBatchCommandGenericContract = Assert<
    IsExact<
        CommandGenerics<RuntimeCreateTaskBatch>,
        [RuntimeCreateTaskBatchContent, RuntimeTaskCreated[]]
    >
>;
const commandResponseContracts: readonly [
    KotlinPairResponseContract,
    JavaPairResponseContract,
    KotlinNestedResponseContract,
    KotlinHandledOnlyResponseContract,
    RuntimeBatchResponseContract,
    RuntimeBatchCommandGenericContract,
] = [true, true, true, true, true, true];

const runtimeBatchCommand = new RuntimeCreateTaskBatch();
const runtimeBatchResponse = new RuntimeTaskCreated();
const runtimeBatchResponses: CommandResponse<RuntimeCreateTaskBatch> = [
    runtimeBatchResponse,
];
const kotlinPairResponseCommand = new KotlinPairResponseCommand();
const javaPairResponseCommand = new JavaPairResponseCommand();
const kotlinNestedResponseCommand = new KotlinNestedResponseCommand();
const kotlinHandledOnlyResponseCommand = new KotlinHandledOnlyResponseCommand();

const suspendCommand = new KotlinSuspendCommand();
suspendCommand.commandId = "suspend";
suspendCommand.optionalNote = undefined;

const response = new FixtureResponse();
response.identifier = metadataCommand.commandId;
response.state = FixtureState.new;
response.labels = ["one"];
response.optionalLabel = undefined;
response.cycle = new CyclicFixture();
response.cycle.name = "root";
response.cycle.next = undefined;
response.explicitState = ExplicitFixtureState.ready;
response.annotatedState = AnnotatedFixtureState.computed;
response.permissions = allFixturePermissions;
response.shape = { name: "circle" };
response.shapes = [{ name: "circle" }, { name: "rectangle" }];
response.javaState = JavaFixtureState.READY;
response.javaAnnotatedState = JavaAnnotatedFixtureState.COMPUTED;
response.javaPermissions = allJavaFixturePermissions;
response.javaContract = { label: "java" };

const circle = new FixtureCircle();
circle.createdAt = new Date();
circle.created = new Date();
circle.radius = 2;
const rectangle = new FixtureRectangle();
rectangle.createdAt = new Date();
rectangle.created = new Date();
rectangle.width = 4;
rectangle.height = 3;
const shape: FixtureShape = response.shape;
const javaContract: JavaFixtureContract = { label: "java" };
const permissions: FixturePermissions =
    FixturePermissions.read | FixturePermissions.write;
const javaPermissions: JavaFixturePermissions = JavaFixturePermissions.READ;

const filter = new FixtureFilter();
filter.ids = [metadataCommand.commandId];
filter.state = FixtureState.active;

const all = new All();
all.prefix = "prefix";
const allParameters: AllParameters = { prefix: "prefix" };
// @ts-expect-error QueryRequest is infrastructure-owned and cannot be supplied by proxy callers.
allParameters.request = {};
// @ts-expect-error QueryContext is infrastructure-owned and cannot be assigned to a generated query.
all.context = {};
// @ts-expect-error Service parameters are infrastructure-owned and cannot be assigned to a generated query.
all.dependency = {};
const contextualKotlin = new ContextualKotlin();
contextualKotlin.label = "contextual";
const contextualKotlinParameters: ContextualKotlinParameters = {
    label: "contextual",
};
// @ts-expect-error QueryRequest must not leak into one-shot parameter interfaces.
contextualKotlinParameters.request = {};
const defaulted = new Defaulted();
defaulted.required = "root";
const defaultedParameters: DefaultedParameters = { required: "root" };
const defaultedCall = defaulted.perform(defaultedParameters);
type DefaultedParameterOptionality = Assert<
    IsExact<
        [
            DefaultedParameters["count"],
            DefaultedParameters["prefix"],
            DefaultedParameters["required"],
            DefaultedParameters["suffix"],
            Defaulted["count"],
            Defaulted["prefix"],
            Defaulted["suffix"],
        ],
        [
            number | undefined,
            string | undefined,
            string,
            string | undefined,
            number | undefined,
            string | undefined,
            string | undefined,
        ]
    >
>;
const defaultedParameterOptionality: DefaultedParameterOptionality = true;
const observeDefaulted = new ObserveDefaulted();
const observeDefaultedParameters: ObserveDefaultedParameters = {};
type ObservableDefaultOptionality = Assert<
    IsExact<
        [ObserveDefaultedParameters["label"], ObserveDefaulted["label"]],
        [string | undefined, string | undefined]
    >
>;
const observableDefaultOptionality: ObservableDefaultOptionality = true;

const byId = new ById();
byId.identifier = "id";
const byIdParameters: ByIdParameters = { identifier: "id" };
// @ts-expect-error Java service parameters must not leak into one-shot parameter interfaces.
byIdParameters.dependency = {};
const filtered = new Filtered();
filtered.filter = filter;
const optional = new Optional();
optional.label = "optional";
const optionalParameters: OptionalParameters = {};
const page = new Page();
page.pageNumber = 0;
const single = new Single();
single.identifier = "id";

const kotlinModel: KotlinQueryReadModel = new KotlinQueryReadModel();
kotlinModel.value = "kotlin";
const javaModel: JavaQueryReadModel = new JavaQueryReadModel();
javaModel.value = "java";

// Type-check generated hook and paging contracts without invoking React hooks.
type AllUse = ReturnType<typeof All.use>;
const assertAllUse: AllUse extends [
    QueryResultWithState<KotlinQueryReadModel[]>,
    PerformQuery<AllParameters>,
]
    ? true
    : false = true;
// @ts-expect-error Queries without explicit host adapters do not expose paging hooks.
void All.useWithPaging;

type SpringDataUse = ReturnType<typeof SpringDataDirect.use>;
type SpringDataPaging = ReturnType<typeof SpringDataDirect.useWithPaging>;
const assertSpringDataUse: SpringDataUse extends [
    QueryResultWithState<KotlinQueryReadModel[]>,
    PerformQuery<SpringDataDirectParameters>,
    SetSorting,
]
    ? true
    : false = true;
const assertSpringDataPaging: SpringDataPaging extends [
    QueryResultWithState<KotlinQueryReadModel[]>,
    PerformQuery<SpringDataDirectParameters>,
    SetSorting,
    SetPage,
    SetPageSize,
]
    ? true
    : false = true;
void assertAllUse;
void assertSpringDataUse;
void assertSpringDataPaging;

// Type-check the real observable-query client surface in direct and multiplexed modes.
Globals.queryDirectMode = true;
const directKotlinObservable: IObservableQueryFor<KotlinQueryReadModel[]> =
    new ObserveAll();
const directSubscribe: IObservableQueryFor<
    KotlinQueryReadModel[]
>["subscribe"] = directKotlinObservable.subscribe.bind(directKotlinObservable);
const directPerform: IObservableQueryFor<KotlinQueryReadModel[]>["perform"] =
    directKotlinObservable.perform.bind(directKotlinObservable);

Globals.queryDirectMode = false;
const multiplexJavaObservable: IObservableQueryFor<JavaQueryReadModel[]> =
    new ObserveJava();
const multiplexSubscribe: IObservableQueryFor<
    JavaQueryReadModel[]
>["subscribe"] = multiplexJavaObservable.subscribe.bind(
    multiplexJavaObservable,
);
const multiplexPerform: IObservableQueryFor<JavaQueryReadModel[]>["perform"] =
    multiplexJavaObservable.perform.bind(multiplexJavaObservable);

const kotlinObservable = new ObserveAll();
kotlinObservable.label = "observable";
const observeAllParameters: ObserveAllParameters = { label: "observable" };
// @ts-expect-error QueryContext must not leak into observable parameter interfaces.
observeAllParameters.context = {};
// @ts-expect-error QueryRequest must not become an observable query property.
kotlinObservable.request = {};
type KotlinObservableSubscription = ReturnType<
    typeof kotlinObservable.subscribe
>;
const assertKotlinObservableSubscription: KotlinObservableSubscription extends ObservableQuerySubscription<
    KotlinQueryReadModel[]
>
    ? true
    : false = true;
const kotlinSingleObservable: IObservableQueryFor<KotlinQueryReadModel> =
    new ObserveSingle();

// Type-check generated observable hooks without invoking React hooks.
type KotlinObservableUse = ReturnType<typeof ObserveAll.use>;
type KotlinObservableSuspense = ReturnType<typeof ObserveAll.useSuspense>;
// @ts-expect-error Observable queries without explicit host adapters do not expose paging hooks.
void ObserveAll.useWithPaging;
// @ts-expect-error Observable queries without explicit host adapters do not expose suspense paging hooks.
void ObserveAll.useSuspenseWithPaging;
type KotlinObservableChanges = ReturnType<typeof ObserveAll.useChangeStream>;
type KotlinObservableWhen = ReturnType<typeof ObserveAll.when>;
type KotlinSingleUse = ReturnType<typeof ObserveSingle.use>;
type KotlinSingleSuspense = ReturnType<typeof ObserveSingle.useSuspense>;
type KotlinSingleWhen = ReturnType<typeof ObserveSingle.when>;
type JavaObservableUse = ReturnType<typeof ObserveJava.use>;

const assertKotlinObservableUse: KotlinObservableUse extends [
    QueryResultWithState<KotlinQueryReadModel[]>,
]
    ? true
    : false = true;
const assertKotlinObservableSuspense: KotlinObservableSuspense extends [
    QueryResultWithState<KotlinQueryReadModel[]>,
]
    ? true
    : false = true;
const assertKotlinObservableChanges: KotlinObservableChanges extends ChangeSet<KotlinQueryReadModel>
    ? true
    : false = true;
const assertKotlinObservableWhen: KotlinObservableWhen extends ObservableQueryWhen<
    ObserveAll,
    KotlinQueryReadModel[]
>
    ? true
    : false = true;
const assertKotlinSingleUse: KotlinSingleUse extends [
    QueryResultWithState<KotlinQueryReadModel>,
]
    ? true
    : false = true;
const assertKotlinSingleSuspense: KotlinSingleSuspense extends [
    QueryResultWithState<KotlinQueryReadModel>,
]
    ? true
    : false = true;
const assertKotlinSingleWhen: KotlinSingleWhen extends ObservableQueryWhen<
    ObserveSingle,
    KotlinQueryReadModel
>
    ? true
    : false = true;
const assertJavaObservableUse: JavaObservableUse extends [
    QueryResultWithState<JavaQueryReadModel[]>,
]
    ? true
    : false = true;

void [
    directSubscribe,
    directPerform,
    multiplexSubscribe,
    multiplexPerform,
    assertKotlinObservableSubscription,
    kotlinSingleObservable,
    assertKotlinObservableUse,
    assertKotlinObservableSuspense,
    assertKotlinObservableChanges,
    assertKotlinObservableWhen,
    assertKotlinSingleUse,
    assertKotlinSingleSuspense,
    assertKotlinSingleWhen,
    assertJavaObservableUse,
];

// Keep every constructed proxy and model live for strict type checking.
void [
    kotlinCommand,
    javaCommand,
    defaulted,
    defaultedCall,
    defaultedParameterOptionality,
    observeDefaulted,
    observeDefaultedParameters,
    observableDefaultOptionality,
    metadataCommand,
    kotlinTemporalCommandContent,
    kotlinTemporalCommand,
    javaTemporalCommandContent,
    javaTemporalCommand,
    kotlinTemporalResult,
    javaTemporalResult,
    kotlinTemporalModel,
    javaTemporalModel,
    findKotlinTemporalParameters,
    findKotlinTemporal,
    findJavaTemporalParameters,
    findJavaTemporal,
    conceptTemporalModel,
    findConceptTemporalParameters,
    findConceptTemporal,
    temporalTypeContracts,
    recursiveMapContracts,
    eventCommand,
    kotlinMapCommand,
    javaMapCommand,
    commandResponseContracts,
    runtimeBatchCommand,
    runtimeBatchResponses,
    kotlinPairResponseCommand,
    javaPairResponseCommand,
    kotlinNestedResponseCommand,
    kotlinHandledOnlyResponseCommand,
    suspendCommand,
    response,
    circle,
    rectangle,
    shape,
    javaContract,
    permissions,
    javaPermissions,
    all,
    byId,
    filtered,
    optional,
    optionalParameters,
    page,
    single,
    kotlinModel,
    javaModel,
];
