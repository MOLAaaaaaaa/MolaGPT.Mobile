package com.molagpt.app.feature.settings

import com.molagpt.app.core.model.ModelsDevPrice
import com.molagpt.app.core.model.ProviderModel

data class ModelPriceReview(
    val providerId: String,
    val providerName: String,
    val model: ProviderModel,
    val candidates: List<ModelsDevPrice>,
    val preferredSource: String?,
)

data class PricingRefreshState(
    val busy: Boolean = false,
    val cachedAt: Long? = null,
    val result: String? = null,
    val reviews: List<ModelPriceReview> = emptyList(),
)
