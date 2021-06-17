package examples.enumPolymorphicDiscriminator.models

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import javax.validation.constraints.NotNull
import kotlin.String

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "some_enum",
    visible = true
)
@JsonSubTypes(
    JsonSubTypes.Type(
        value = ConcreteImplOne::class,
        name =
        "obj_one_only"
    ),
    JsonSubTypes.Type(
        value = ConcreteImplTwo::class,
        name =
        "obj_two_first"
    ),
    JsonSubTypes.Type(value = ConcreteImplTwo::class, name = "obj_two_second")
)
sealed class PolymorphicEnumDiscriminator() {
    abstract val someEnum: EnumDiscriminator
}

enum class EnumDiscriminator(
    @JsonValue
    val value: String
) {
    OBJ_ONE_ONLY("obj_one_only"),

    OBJ_TWO_FIRST("obj_two_first"),

    OBJ_TWO_SECOND("obj_two_second");
}

data class ConcreteImplOne(
    @param:JsonProperty("some_prop")
    @get:JsonProperty("some_prop")
    val someProp: String? = null
) : PolymorphicEnumDiscriminator() {
    @get:JsonProperty("some_enum")
    @get:NotNull
    override val someEnum: EnumDiscriminator = EnumDiscriminator.OBJ_ONE_ONLY
}

data class ConcreteImplTwo(
    @param:JsonProperty("some_enum")
    @get:JsonProperty("some_enum")
    @get:NotNull
    override val someEnum: EnumDiscriminator,
    @param:JsonProperty("some_prop")
    @get:JsonProperty("some_prop")
    val someProp: String? = null
) : PolymorphicEnumDiscriminator()
