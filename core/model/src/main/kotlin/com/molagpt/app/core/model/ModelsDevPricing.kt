package com.molagpt.app.core.model

import java.net.URI
import kotlinx.serialization.json.*

data class ModelsDevProvider(val key: String, val name: String, val models: Map<String, ModelPricing>)
data class ModelsDevPrice(val providerKey: String, val providerName: String, val pricing: ModelPricing)
data class PricingMatch(
    val agreed: Map<String, ModelPricing>,
    val conflicts: Map<String, List<ModelsDevPrice>>,
    val unmatched: List<String>,
)

object ModelsDevPricing {
    fun parse(raw: String): List<ModelsDevProvider> = Json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
        val provider = value as? JsonObject ?: return@mapNotNull null
        val models = provider["models"] as? JsonObject ?: return@mapNotNull null
        val prices = models.mapNotNull { (id, model) ->
            val cost = (model as? JsonObject)?.get("cost") as? JsonObject ?: return@mapNotNull null
            readPricing(cost, "input", "output", "cache_read", "cache_write", 1.0, "models.dev")
                ?.let { id.lowercase() to it }
        }.toMap()
        if (prices.isEmpty()) null else ModelsDevProvider(key, provider["name"]?.jsonPrimitive?.contentOrNull ?: key, prices)
    }

    fun match(providers: List<ModelsDevProvider>, modelIds: List<String>): PricingMatch {
        val agreed = mutableMapOf<String, ModelPricing>()
        val conflicts = mutableMapOf<String, List<ModelsDevPrice>>()
        val unmatched = mutableListOf<String>()
        for (id in modelIds.distinct()) {
            val prices = providers.mapNotNull { provider ->
                provider.models[id.lowercase()]?.let { ModelsDevPrice(provider.key, provider.name, it) }
            }
            when {
                prices.isEmpty() -> unmatched.add(id)
                prices.map { it.pricing }.distinct().size == 1 -> agreed[id] = prices.first().pricing
                else -> conflicts[id] = prices
            }
        }
        return PricingMatch(agreed, conflicts, unmatched)
    }

    fun guessProviderKey(baseUrl: String): String? = runCatching {
        URI(baseUrl).host?.lowercase()?.split('.')?.firstOrNull {
            it !in setOf("www", "api", "com", "org", "net", "ai", "io", "cn", "co")
        }
    }.getOrNull()
}

fun readEndpointPricing(item: JsonObject): ModelPricing? {
    val cost = item["pricing"] as? JsonObject ?: return null
    return readPricing(cost, "prompt", "completion", "input_cache_read", "input_cache_write", 1_000_000.0, "endpoint")
}

private fun readPricing(obj: JsonObject, input: String, output: String, read: String, write: String, scale: Double, source: String): ModelPricing? {
    fun rate(key: String) = (obj[key] as? JsonPrimitive)?.doubleOrNull?.times(scale)
    val price = ModelPricing(rate(input) ?: return null, rate(output) ?: return null, rate(read), rate(write), source)
    return price.takeIf { it.isValid }
}
