/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.optionsmap

import org.eclipse.collections.api.PrimitiveIterable
import org.neo4j.configuration.Config
import org.neo4j.cypher.internal.CypherVersion
import org.neo4j.cypher.internal.runtime.IndexProviderContext
import org.neo4j.gqlstatus.GqlHelper.getGql22G03_22N27
import org.neo4j.gqlstatus.GqlParams
import org.neo4j.internal.schema.IndexConfig
import org.neo4j.internal.schema.IndexConfigValidationRecords
import org.neo4j.internal.schema.IndexConfigValidationRecords.IncorrectType
import org.neo4j.internal.schema.IndexConfigValidationRecords.IndexConfigValidationRecord
import org.neo4j.internal.schema.IndexConfigValidationRecords.InvalidValue
import org.neo4j.internal.schema.IndexConfigValidationRecords.State.INCORRECT_TYPE
import org.neo4j.internal.schema.IndexConfigValidationRecords.State.INVALID_VALUE
import org.neo4j.internal.schema.IndexConfigValidationRecords.State.MISSING_SETTING
import org.neo4j.internal.schema.IndexConfigValidationRecords.State.UNRECOGNIZED_SETTING
import org.neo4j.internal.schema.IndexProviderDescriptor
import org.neo4j.internal.schema.IndexType
import org.neo4j.internal.schema.SettingsAccessor.MapValueAccessor
import org.neo4j.kernel.api.exceptions.InvalidArgumentsException
import org.neo4j.kernel.api.impl.schema.vector.VectorIndexConfigUtils
import org.neo4j.kernel.api.impl.schema.vector.VectorIndexVersion
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.BooleanValue
import org.neo4j.values.storable.IntegralValue
import org.neo4j.values.storable.TextValue
import org.neo4j.values.utils.PrettyPrinter
import org.neo4j.values.virtual.MapValue

import java.lang

import scala.jdk.CollectionConverters.IterableHasAsScala

case class CreateVectorIndexOptionsConverter(context: IndexProviderContext, latestSupportedVersion: VectorIndexVersion)
    extends IndexOptionsConverter[CreateIndexWithFullOptions] {
  private val schemaType = "vector index"

  override protected val hasMandatoryOptions: Boolean = true

  override def convert(
    options: MapValue,
    config: Option[Config],
    cypherVersion: CypherVersion
  ): OptionsConverterResult[CreateIndexWithFullOptions] = {
    val (indexProvider, indexConfig, notifications) =
      getOptionsParts(options, schemaType, IndexType.VECTOR, cypherVersion)
    ParsedWithNotifications(CreateIndexWithFullOptions(indexProvider, indexConfig), notifications)
  }

  // VECTOR indexes has vector config settings
  override protected def assertValidAndTransformConfig(
    config: AnyValue,
    schemaType: String,
    maybeIndexProvider: Option[IndexProviderDescriptor]
  ): IndexConfig = {
    // current keys: vector.(dimensions|similarity_function|quantization.enabled|hnsw.m|hnsw.ef_construction)
    // current values: Long, String, Boolean

    def assertInvalidConfigValues(
      pp: PrettyPrinter,
      validationRecords: IndexConfigValidationRecords,
      itemsMap: MapValue,
      schemaType: String,
      validSettingNames: Iterable[String]
    ): Unit = {
      validationRecords.get(UNRECOGNIZED_SETTING).asScala.foreach {
        case fulltextSetting if validFulltextConfigSettingNames.contains(fulltextSetting.settingName) =>
          foundFulltextConfigValues(IndexType.VECTOR, pp, itemsMap, schemaType)
        case pointSetting if validPointConfigSettingNames.contains(pointSetting.settingName) =>
          foundPointConfigValues(IndexType.VECTOR, pp, itemsMap, schemaType)
        case unrecognized => throw new InvalidArgumentsException(
            invalidConfigValueString(pp, itemsMap, schemaType) +
              s". '${unrecognized.settingName}' is an unrecognized setting. Supported: " +
              validSettingNames.mkString("[", ", ", "]")
          )
      }
    }

    def assertMandatoryConfigSettingsExists(validationRecords: IndexConfigValidationRecords): Unit = {
      val missingSettings = validationRecords.get(MISSING_SETTING)
      if (!missingSettings.isEmpty) {
        val missing =
          missingSettings.makeString(
            (r: IndexConfigValidationRecord) => s"'${r.settingName}'",
            "[",
            ", ",
            "]"
          )
        throw new InvalidArgumentsException(
          s"Failed to create $schemaType: Missing index config options $missing."
        )
      }
    }

    def exceptionWrongType(suppliedValue: AnyValue): InvalidArgumentsException = {
      val pp = new PrettyPrinter
      suppliedValue.writeTo(pp)
      val gql = getGql22G03_22N27(
        pp.value,
        GqlParams.StringParam.cmd.process("indexConfig"),
        java.util.List.of("MAP<STRING, BOOLEAN | STRING | INTEGER>")
      )
      new InvalidArgumentsException(
        gql,
        s"${invalidConfigValueString(new PrettyPrinter(), suppliedValue, schemaType)}. Expected a map from String to Strings, Integers and Booleans."
      )
    }

    def assertConfigSettingsCorrectTypes(validationRecords: IndexConfigValidationRecords, itemsMap: MapValue): Unit = {
      // note: in cypher 25 probably should refer to these as INTEGER and STRING respectively
      val validTypes: Map[Class[_], String] =
        Map(
          classOf[IntegralValue] -> "an Integer",
          classOf[TextValue] -> "a String",
          classOf[BooleanValue] -> "a Boolean"
        )
      val validCypherTypes: Map[Class[_], String] =
        Map(
          classOf[IntegralValue] -> "INTEGER",
          classOf[TextValue] -> "STRING",
          classOf[BooleanValue] -> "BOOLEAN"
        )

      validationRecords.get(INCORRECT_TYPE).asScala.foreach {
        // valid type for vector index config, *but* invalid for that setting
        case incorrectType: IncorrectType if validTypes.exists { case (cls, _) =>
            cls.isAssignableFrom(incorrectType.providedType)
          } =>
          val gql = getGql22G03_22N27(
            "type " + incorrectType.providedType,
            GqlParams.StringParam.cmd.process(incorrectType.settingName),
            java.util.List.of(validCypherTypes(incorrectType.targetType()))
          )
          throw new InvalidArgumentsException(
            gql,
            s"${invalidConfigValueString(incorrectType.settingName, schemaType)}. Expected ${validTypes(incorrectType.targetType)}."
          )
        // invalid type for valid type for vector index config
        case _ => throw exceptionWrongType(itemsMap)
      }
    }

    def assertValidConfigValues(pp: PrettyPrinter, validationRecords: IndexConfigValidationRecords): Unit = {
      validationRecords.get(INVALID_VALUE).asScala.map(_.asInstanceOf[InvalidValue]).foreach {
        invalidValue =>
          val valid = invalidValue.valid
          valid match {
            case range: VectorIndexConfigUtils.Range[_] => throw new IllegalArgumentException(
                s"'${invalidValue.settingName}' must be between ${range.min} and ${range.max} inclusively"
              )
            case _: lang.Iterable[_] | _: PrimitiveIterable =>
              val supported = valid match {
                case iterable: lang.Iterable[_]           => iterable.asScala.mkString("[", ", ", "]")
                case primitiveIterable: PrimitiveIterable => primitiveIterable.makeString("[", ", ", "]")
                case _                                    => // by construction, this pattern match is exhaustive
              }
              invalidValue.rawValue().writeTo(pp)
              throw new IllegalArgumentException(
                s"'${pp.value()}' is an unsupported '${invalidValue.settingName}'. Supported: $supported"
              )
            case unknown => throw new IllegalStateException(
                s"Unhandled valid value type '${unknown.getClass.getSimpleName}' for '${invalidValue.settingName}'. Provided: $unknown"
              )
          }
      }
    }

    config match {
      case itemsMap: MapValue =>
        val version = maybeIndexProvider match {
          case Some(provider) if provider.getKey == "cuvs" =>
            // CUVS provider - skip Lucene validation since CUVS has its own validation
            // Return empty config to let CUVS provider handle validation
            return IndexConfig.empty
          case Some(provider) =>
            VectorIndexVersion.fromDescriptor(provider)
          case None =>
            latestSupportedVersion
        }
        val validator = version.indexSettingValidator
        val validationRecords = validator.validate(new MapValueAccessor(itemsMap))
        if (validationRecords.valid) return validator.trustIsValidToVectorIndexConfig(validationRecords).config

        assertInvalidConfigValues(
          new PrettyPrinter(),
          validationRecords,
          itemsMap,
          schemaType,
          validator.validSettings.asScala.map(_.getSettingName)
        )
        assertMandatoryConfigSettingsExists(validationRecords)
        assertConfigSettingsCorrectTypes(validationRecords, itemsMap)
        assertValidConfigValues(new PrettyPrinter(), validationRecords)
        validator.trustIsValidToVectorIndexConfig(validationRecords).config
      case unknown =>
        throw exceptionWrongType(unknown)
    }
  }

  override def operation: String = s"create $schemaType"
}
