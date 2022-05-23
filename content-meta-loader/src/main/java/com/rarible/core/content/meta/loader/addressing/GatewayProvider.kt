package com.rarible.core.content.meta.loader.addressing

import java.util.*

interface GatewayProvider {
    fun getGateway(): String
}

class RandomGatewayProvider(
    private val gateways: List<String>
) : GatewayProvider {

    override fun getGateway(): String = gateways[Random().nextInt(gateways.size)]
}

class ConstantGatewayProvider(
    private val gateway: String
) : GatewayProvider {

    override fun getGateway(): String = gateway
}

