/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Removes the KT class from the public API */
@file:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)

package androidx.wear.watchface.complications

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.RectF
import android.util.TypedValue
import androidx.annotation.RestrictTo
import androidx.wear.watchface.complications.data.ComplicationType
import java.io.DataOutputStream

const val NAMESPACE_APP = "http://schemas.android.com/apk/res-auto"
const val NAMESPACE_ANDROID = "http://schemas.android.com/apk/res/android"

/**
 * ComplicationSlotBounds are defined by fractional screen space coordinates in unit-square [0..1].
 * These bounds will be subsequently clamped to the unit square and converted to screen space
 * coordinates. NB 0 and 1 are included in the unit square.
 *
 * One bound is expected per [ComplicationType] to allow [androidx.wear.watchface.ComplicationSlot]s
 * to change shape depending on the type.
 */
public class ComplicationSlotBounds(
    /** Per [ComplicationType] fractional unit-square screen space complication bounds. */
    public val perComplicationTypeBounds: Map<ComplicationType, RectF>
) {
    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun write(dos: DataOutputStream) {
        perComplicationTypeBounds.keys.toSortedSet().forEach { type ->
            val bounds = perComplicationTypeBounds[type]!!
            dos.writeInt(type.toWireComplicationType())
            dos.writeFloat(bounds.left)
            dos.writeFloat(bounds.right)
            dos.writeFloat(bounds.top)
            dos.writeFloat(bounds.bottom)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ComplicationSlotBounds

        return perComplicationTypeBounds == other.perComplicationTypeBounds
    }

    override fun hashCode(): Int {
        return perComplicationTypeBounds.toSortedMap().hashCode()
    }

    /**
     * Constructs a ComplicationSlotBounds where all complication types have the same screen space
     * unit-square bounds.
     */
    public constructor(bounds: RectF) : this(ComplicationType.values().associateWith { bounds })

    init {
        require(perComplicationTypeBounds.size == ComplicationType.values().size) {
            "ComplicationSlotBounds must contain entries for each ComplicationType"
        }
        for (type in ComplicationType.values()) {
            require(perComplicationTypeBounds.containsKey(type)) {
                "Missing bounds for $type"
            }
        }
    }

    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        internal const val NODE_NAME = "ComplicationSlotBounds"

        /**
         * Constructs a [ComplicationSlotBounds] from a potentially incomplete
         * Map<ComplicationType, RectF>, backfilling with empty [RectF]s. This method is necessary
         * because there can be a skew between the version of the library between the watch face and
         * the system which would otherwise be problematic if new complication types have been
         * introduced.
         * @hide
         */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun createFromPartialMap(
            partialPerComplicationTypeBounds: Map<ComplicationType, RectF>
        ): ComplicationSlotBounds {
            val map = HashMap(partialPerComplicationTypeBounds)

            for (type in ComplicationType.values()) {
                map.putIfAbsent(type, RectF())
            }

            return ComplicationSlotBounds(map)
        }

        /**
         * The [parser] should be inside a node with any number of ComplicationSlotBounds child
         * nodes. No other child nodes are expected.
         */
        fun inflate(
            resources: Resources,
            parser: XmlResourceParser
        ): ComplicationSlotBounds? {
            val perComplicationTypeBounds by lazy { HashMap<ComplicationType, RectF>() }
            parser.iterate {
                when (parser.name) {
                    NODE_NAME -> {
                        val rect = if (parser.hasValue("left"))
                            RectF(
                                parser.requireAndGet("left", resources),
                                parser.requireAndGet("top", resources),
                                parser.requireAndGet("right", resources),
                                parser.requireAndGet("bottom", resources)
                            )
                        else if (parser.hasValue("center_x")) {
                            val halfWidth = parser.requireAndGet("size_x", resources) / 2.0f
                            val halfHeight = parser.requireAndGet("size_y", resources) / 2.0f
                            val centerX = parser.requireAndGet("center_x", resources)
                            val centerY = parser.requireAndGet("center_y", resources)
                            RectF(
                                centerX - halfWidth,
                                centerY - halfHeight,
                                centerX + halfWidth,
                                centerY + halfHeight
                            )
                        } else {
                            throw IllegalArgumentException("$NODE_NAME must " +
                                "either define top, bottom, left, right" +
                                "or center_x, center_y, size_x, size_y should be specified")
                        }
                        if (null != parser.getAttributeValue(
                                NAMESPACE_APP,
                                "complicationType"
                            )
                        ) {
                            val complicationType = ComplicationType.fromWireType(
                                parser.getAttributeIntValue(
                                    NAMESPACE_APP,
                                    "complicationType",
                                    0
                                )
                            )
                            require(
                                !perComplicationTypeBounds.contains(complicationType)
                            ) {
                                "Duplicate $complicationType"
                            }
                            perComplicationTypeBounds[complicationType] = rect
                        } else {
                            for (complicationType in ComplicationType.values()) {
                                require(
                                    !perComplicationTypeBounds.contains(
                                        complicationType
                                    )
                                ) {
                                    "Duplicate $complicationType"
                                }
                                perComplicationTypeBounds[complicationType] = rect
                            }
                        }
                    }
                    else -> throw IllegalNodeException(parser)
                }
            }

            return if (perComplicationTypeBounds.isEmpty()) {
                null
            } else {
                createFromPartialMap(perComplicationTypeBounds)
            }
        }
    }
}

internal fun XmlResourceParser.requireAndGet(
    id: String,
    resources: Resources
): Float {
    val stringValue = getAttributeValue(NAMESPACE_APP, id)
    require(null != stringValue) {
        "${ComplicationSlotBounds.NODE_NAME} must define '$id'"
    }

    val resId = getAttributeResourceValue(NAMESPACE_APP, id, 0)
    if (resId != 0) {
        return resources.getDimension(resId) / resources.displayMetrics.widthPixels
    }

    // There is "dp" -> "dip" conversion while resources compilation.
    val dpStr = "dip"

    if (stringValue.endsWith(dpStr)) {
        val dps = stringValue.substring(0, stringValue.length - dpStr.length).toFloat()
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dps,
            resources.displayMetrics
        ) / resources.displayMetrics.widthPixels
    } else {
        stringValue.toFloat()
    }

    return getAttributeFloatValue(NAMESPACE_APP, id, 0f)
}

fun XmlResourceParser.hasValue(id: String): Boolean {
    return null != getAttributeValue(NAMESPACE_APP, id)
}
