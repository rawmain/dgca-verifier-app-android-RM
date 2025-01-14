/*
 *  ---license-start
 *  eu-digital-green-certificates / dcc-revocation-app-android
 *  ---
 *  Copyright (C) 2022 T-Systems International GmbH and all other contributors
 *  ---
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  ---license-end
 *
 *  Created by mykhailo.nester on 20/01/2022, 18:01
 */

package dcc.app.revocation.domain.usacase

import dcc.app.revocation.data.network.model.SliceType
import dcc.app.revocation.domain.ErrorHandler
import dcc.app.revocation.domain.RevocationRepository
import dcc.app.revocation.domain.hexToByteArray
import dcc.app.revocation.domain.model.DccRevocationHashType
import dcc.app.revocation.domain.model.DccRevocationMode
import dcc.app.revocation.domain.model.DccRevocationDataHolder
import dcc.app.revocation.validation.bloom.BloomFilterImpl
import dcc.app.revocation.validation.hash.PartialVariableHashFilter
import kotlinx.coroutines.CoroutineDispatcher
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class IsDccRevokedUseCase @Inject constructor(
    private val repository: RevocationRepository,
    dispatcher: CoroutineDispatcher,
    errorHandler: ErrorHandler,
) : BaseUseCase<Boolean, DccRevocationDataHolder>(dispatcher, errorHandler) {

    override suspend fun invoke(params: DccRevocationDataHolder): Boolean {
        Timber.d("Revocation check start")
        val kid = params.kid
        val kidMetadata = repository.getMetadataByKid(kid)
        kidMetadata ?: return false

        val mode = kidMetadata.mode
        if (kidMetadata.hashType.contains(DccRevocationHashType.UCI)) {
            Timber.d("UCI Hash: ${params.uvciSha256}")
            val containsUvciSha256 = isContainsHash(kid, mode, params.uvciSha256)
            if (containsUvciSha256) {
                Timber.d("Revocation check end. uci:$containsUvciSha256")
                return true
            }
        }

        if (kidMetadata.hashType.contains(DccRevocationHashType.COUNTRYCODEUCI)) {
            Timber.d("COUNTRYCODEUCI Hash: ${params.coUvciSha256}")
            val containsCoUvciSha256 = isContainsHash(kid, mode, params.coUvciSha256)
            if (containsCoUvciSha256) {
                Timber.d("Revocation check end. co+uci:$containsCoUvciSha256")
                return true
            }
        }

        if (kidMetadata.hashType.contains(DccRevocationHashType.SIGNATURE)) {
            Timber.d("SIGNATURE Hash: ${params.signatureSha256}")
            val containsSignatureSha256 = isContainsHash(kid, mode, params.signatureSha256)
            if (containsSignatureSha256) {
                Timber.d("Revocation check end. signature:$containsSignatureSha256")
                return true
            }
        }

        return false
    }

    private suspend fun isContainsHash(
        kid: String,
        mode: DccRevocationMode,
        hash: String?
    ): Boolean {
        hash ?: return false

        var x: Char? = null
        var y: Char? = null
        val cid: Char

        when (mode) {
            DccRevocationMode.POINT -> {
                cid = hash[0]
            }
            DccRevocationMode.VECTOR -> {
                cid = hash[1]
                x = hash[0]
            }
            DccRevocationMode.COORDINATE -> {
                cid = hash[2]
                x = hash[0]
                y = hash[1]
            }
            DccRevocationMode.UNKNOWN -> return false
        }

        val validationData = getValidationData(kid, x, y, cid.toString())
        return contains(hash, validationData)
    }

    private suspend fun getValidationData(
        kid: String,
        x: Char?,
        y: Char?,
        cid: String
    ): ValidationData {
        val bloomFilterList = mutableSetOf<ByteArray>()
        val hashList = mutableSetOf<ByteArray>()

        val currentTime = ChronoUnit.MICROS.between(Instant.EPOCH, ZonedDateTime.now().toInstant())
        val result = repository.getChunkSlices(kid, x, y, cid, currentTime)
        result.iterator().forEach {
            Timber.d("Slice found: $it")
            when (it.type) {
                SliceType.VARHASHLIST -> hashList.add(it.content)
                SliceType.BLOOMFILTER -> bloomFilterList.add(it.content)
            }
        }

        return ValidationData(x, y, bloomFilterList, hashList)
    }

    private fun contains(dccHash: String, validationData: ValidationData?): Boolean {
        validationData ?: return false

        Timber.d("bloom filter slices: ${validationData.bloomFilterList.size}")
        validationData.bloomFilterList.iterator().forEach {
            val inputStream: InputStream = ByteArrayInputStream(it)
            val bloomFilter = BloomFilterImpl(inputStream)
            val contains = bloomFilter.mightContain(dccHash.hexToByteArray())
            if (contains) {
                Timber.d("dcc revoked bloomfilter: $dccHash")
                return true
            }
        }

        Timber.d("hash filter slices: ${validationData.hashFilterList.size}")
        validationData.hashFilterList.iterator().forEach {
            val filter = PartialVariableHashFilter(it)
            val contains = filter.mightContain(dccHash.hexToByteArray())
            if (contains) {
                Timber.d("dcc revoked HashVariable: $dccHash")
                return true
            }
        }

        return false
    }

    internal data class ValidationData(
        val x: Char?,
        val y: Char?,
        val bloomFilterList: Set<ByteArray>,
        val hashFilterList: Set<ByteArray>
    )
}
