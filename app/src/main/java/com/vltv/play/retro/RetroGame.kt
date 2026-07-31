package com.vltv.play.retro

import com.google.gson.annotations.SerializedName

/**
 * Representa um jogo retrô vindo do catálogo (games.json) hospedado na VPS.
 */
data class RetroGame(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("console") val console: String,
    @SerializedName("core") val core: String,      // nes, snes, gba, gb, gbc, genesis, psx, n64...
    @SerializedName("rom") val romUrl: String,
    @SerializedName("cover") val coverUrl: String?
)
