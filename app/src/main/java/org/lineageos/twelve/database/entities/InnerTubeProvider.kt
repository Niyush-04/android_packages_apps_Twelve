/*
 * SPDX-FileCopyrightText: 2024-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * InnerTube (YouTube Music) provider entity.
 *
 * @param id   Unique ID of this instance
 * @param name Display name chosen by the user
 * @param cookie Optional YouTube Music cookie for authenticated access.
 */
@Entity
data class InnerTubeProvider(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "innertube_provider_id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cookie") val cookie: String?,
)
