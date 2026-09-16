// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Runtime.InteropServices;
using System.Text.Json;
using Cratis.Arc.Authorization;
using Cratis.Arc.Commands.ModelBound;
using Cratis.Arc.Identity;
using Cratis.Arc.Queries.ModelBound;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Mvc;

CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = args, EnvironmentName = Environments.Production });
builder.Configuration.Sources.Clear();
builder.Logging.ClearProviders();
builder.WebHost.ConfigureKestrel(options => options.Listen(IPAddress.Loopback, 0));
builder.Services.AddSingleton<HttpConformance.TaskStore>();
builder.AddCratisArc(configureOptions: options =>
{
    options.IdentityDetailsProvider = typeof(DefaultIdentityDetailsProvider);
    options.ExposeExceptionDetails = false;
    options.GeneratedApis.RoutePrefix = "api";
    options.GeneratedApis.SegmentsToSkipForRoute = 1;
    options.GeneratedApis.IncludeCommandNameInRoute = true;
    options.GeneratedApis.IncludeQueryNameInRoute = true;
    options.GeneratedApis.EnableQueryHttpMethod = true;
});
await using var app = builder.Build();
app.UseRouting();
app.UseWebSockets();
app.UseCratisArc();
await app.StartAsync();
var addresses = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()
    ?? throw new InvalidOperationException("No server address feature");
Console.WriteLine(JsonSerializer.Serialize(new
{
    kind = "http-conformance-ready",
    baseUrl = addresses.Addresses.Single(),
    runtime = Environment.Version.ToString(),
    coreRuntimeDirectory = RuntimeEnvironment.GetRuntimeDirectory(),
    aspNetCoreAssembly = typeof(WebApplication).Assembly.Location
}));
await app.WaitForShutdownAsync();

namespace HttpConformance
{
    [Command]
    [AllowAnonymous]
    public record CreateTask(string Title)
    {
        public TaskCreated Handle(TaskStore store)
        {
            var task = store.Create(Title);
            return new(task.Id, task.Title);
        }
    }

    [Command]
    [AllowAnonymous]
    public record CompleteTask(string TaskId)
    {
        public TaskView Handle(TaskStore store) => store.Complete(TaskId);
    }

    public record TaskCreated(string Id, string Title);

    [ReadModel]
    [AllowAnonymous]
    public record TaskView(string Id, string Title, bool Completed)
    {
        [Cratis.Arc.Queries.ModelBound.Path("/api/tasks/by-id")]
        public static TaskView? ById(string id, [FromServices] TaskStore store) => store.ById(id);

        [Cratis.Arc.Queries.ModelBound.Path("/api/tasks")]
        public static IEnumerable<TaskView> All([FromServices] TaskStore store) => store.All();
    }

    public sealed class TaskStore
    {
        readonly ConcurrentDictionary<string, TaskView> _tasks = new();
        public TaskView Create(string title)
        {
            var value = new TaskView(Guid.NewGuid().ToString(), title.Trim(), false);
            if (!_tasks.TryAdd(value.Id, value)) throw new InvalidOperationException("Duplicate task identity");
            return value;
        }
        public TaskView? ById(string id) => _tasks.GetValueOrDefault(id);
        public IEnumerable<TaskView> All() => _tasks.Values.OrderBy(task => task.Id).ToArray();
        public TaskView Complete(string id)
        {
            var current = _tasks[id];
            var updated = current with { Completed = true };
            if (!_tasks.TryUpdate(id, updated, current)) throw new InvalidOperationException("Concurrent task mutation");
            return updated;
        }
    }
}
