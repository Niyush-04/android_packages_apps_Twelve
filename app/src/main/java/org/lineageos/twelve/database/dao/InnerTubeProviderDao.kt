/*
 * SPDX-FileCopyrightText: 2024-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.lineageos.twelve.database.entities.InnerTubeProvider

@Dao
interface InnerTubeProviderDao {
    /**
     * Add a new Innertube provider to the database.
     */
    @Query(
        """
            INSERT INTO InnerTubeProvider (name, cookie)
            VALUES (:name, :cookie)
        """
    )
    suspend fun create(
        name: String?,
        cookie: String?,
    ): Long

    /**
     * Update a InnerTube provider.
     */
    @Query(
        """
            UPDATE InnerTubeProvider
            SET name   = :name,
                cookie = :cookie
            WHERE innertube_provider_id = :innerTubeProviderId
        """
    )
    suspend fun update(
        innerTubeProviderId: Long,
        name: String,
        cookie: String?,
    )

    /**
     * Delete a InnerTube provider from the database.
     */
    @Query("DELETE FROM InnerTubeProvider WHERE innertube_provider_id = :innerTubeProviderId")
    suspend fun delete(innerTubeProviderId: Long)

    /**
     * Fetch all InnerTube providers from the database.
     */
    @Query("SELECT * FROM InnerTubeProvider")
    fun getAll(): Flow<List<InnerTubeProvider>>
}
