// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import {
    All as KotlinAll,
    ById as KotlinById,
    CreateTask as KotlinCreateTask,
    RenameTask as KotlinRenameTask,
    TaskView as KotlinTaskView,
} from "../generated/chronicle/kotlin";
import {
    All as JavaAll,
    ById as JavaById,
    CreateTask as JavaCreateTask,
    RenameTask as JavaRenameTask,
    TaskView as JavaTaskView,
} from "../generated/chronicle/java";

const kotlinCreate = new KotlinCreateTask();
kotlinCreate.id = "task-1";
kotlinCreate.title = "Kotlin task";

const kotlinRename = new KotlinRenameTask();
kotlinRename.id = "task-1";
kotlinRename.title = "Renamed Kotlin task";
kotlinRename.expectedSequenceNumber = 0;

const kotlinById = new KotlinById();
kotlinById.id = "task-1";
const kotlinAll = new KotlinAll();
const kotlinView: KotlinTaskView = new KotlinTaskView();

const javaCreate = new JavaCreateTask();
javaCreate.id = "task-1";
javaCreate.title = "Java task";

const javaRename = new JavaRenameTask();
javaRename.id = "task-1";
javaRename.title = "Renamed Java task";
javaRename.expectedSequenceNumber = 0;

const javaById = new JavaById();
javaById.id = "task-1";
const javaAll = new JavaAll();
const javaView: JavaTaskView = new JavaTaskView();

void [
    kotlinCreate,
    kotlinRename,
    kotlinById,
    kotlinAll,
    kotlinView,
    javaCreate,
    javaRename,
    javaById,
    javaAll,
    javaView,
];
