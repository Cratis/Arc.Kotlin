// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using Cratis.Arc.Commands.ModelBound;
using Cratis.Arc.Queries.ModelBound;

namespace Arc.Kotlin.Differential.Fixture;

/// <summary>
/// The expected side of the .NET-derived proxy differential.
///
/// Authored here rather than copied from a sibling checkout so the capture is reproducible from published
/// packages alone. It deliberately includes Guid, DateOnly and TimeOnly, which the earlier hand-prepared
/// fixture did not carry.
/// </summary>
public enum OrderKind
{
    Standard = 0,
    Express = 1
}

/// <summary>A nested model reached only through another model's property.</summary>
public record Address(string Street, string City);

/// <summary>A command carrying the scalar, temporal, enum and nested shapes the differential compares.</summary>
[Command]
public class PlaceOrder
{
    public Guid Id { get; set; }
    public string Customer { get; set; } = string.Empty;
    public int Quantity { get; set; }
    public bool Express { get; set; }
    public DateOnly RequestedDate { get; set; }
    public TimeOnly RequestedTime { get; set; }
    public DateTimeOffset PlacedAt { get; set; }
    public OrderKind Kind { get; set; }
    public Address ShipTo { get; set; } = new(string.Empty, string.Empty);
    public IEnumerable<string> Tags { get; set; } = [];

    public Guid Handle() => Id;
}

/// <summary>A read model whose static methods become one-shot queries.</summary>
[ReadModel]
public record OrderView(
    Guid Id,
    string Customer,
    OrderKind Kind,
    DateOnly RequestedDate,
    TimeOnly RequestedTime,
    Address ShipTo)
{
    public static IEnumerable<OrderView> AllOrders() => [];

    public static OrderView OrderById(Guid id) =>
        new(id, string.Empty, OrderKind.Standard, default, default, new(string.Empty, string.Empty));
}
