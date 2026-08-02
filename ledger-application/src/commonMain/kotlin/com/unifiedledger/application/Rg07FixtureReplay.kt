package com.unifiedledger.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Rg07FixtureOperation(
    val id: String,
    val action: Rg07Action,
    val operationClass: Rg07OperationClass,
    val identity: String?,
    val status: String,
    val input: JsonElement?,
    val attemptedInput: JsonElement?,
    val returnedIds: JsonElement,
    val deltas: JsonElement,
    val statusChanges: JsonElement,
    val baselineStateId: String,
    val resultStateId: String,
)

data class Rg07FixtureReplaySummary(
    val operations: List<Rg07FixtureOperation>,
    val accepted: Int,
    val noChange: Int,
    val rejected: Int,
    val rootCount: Int = 0,
    val stateCount: Int = 0,
)

/** Reads the approved expected artifact without inventing domain state from its projections. */
fun replayRg07Fixture(raw: String): Rg07FixtureReplaySummary {
    val root = Json.parseToJsonElement(raw).jsonObject
    val roots = root["roots"]?.jsonArray
    val states = root["states"]?.jsonArray
    val operations = root.getValue("operations").jsonArray.map { element ->
        val operation = element.jsonObject
        val outcome = operation.getValue("outcome").jsonObject
        val action = Rg07Action.entries.first { it.code == operation.getValue("action_type").jsonPrimitive.content }
        Rg07FixtureOperation(
            id = operation.getValue("id").jsonPrimitive.content,
            action = action,
            operationClass = Rg07OperationClass.entries.first { it.name.equals(operation.getValue("operation_class").jsonPrimitive.content, ignoreCase = true) },
            identity = operation["input"]?.let { input -> input.jsonObject["request_id"]?.jsonPrimitive?.content },
            status = outcome.getValue("status").jsonPrimitive.content,
            input = operation["input"],
            attemptedInput = operation["attempted_input"],
            returnedIds = operation.getValue("returned_ids"),
            deltas = operation.getValue("deltas"),
            statusChanges = operation.getValue("status_changes"),
            baselineStateId = operation.getValue("baseline_state_id").jsonPrimitive.content,
            resultStateId = operation.getValue("result_state_id").jsonPrimitive.content,
        )
    }
    return Rg07FixtureReplaySummary(
        operations = operations,
        accepted = operations.count { it.status == "accepted" },
        noChange = operations.count { it.status == "no_change" },
        rejected = operations.count { it.status == "rejected" },
        rootCount = roots?.size ?: 0,
        stateCount = states?.size ?: 0,
    )
}
