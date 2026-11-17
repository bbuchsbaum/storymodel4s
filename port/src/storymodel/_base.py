"""Shared immutable records and content identity, independent of evidence coordinates."""

from typing import Annotated, Any, ClassVar, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .serialization import canonical, digest

Name = Annotated[str, Field(min_length=1)]
Digest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Side = Literal["source", "recall"]


class Model(BaseModel):
    """Strict immutable records; contextual validity belongs to Account admission."""

    # Public consumers call model_validate on instances before serializing: copying can
    # forge values, while JSON serialization can coerce their types or discard unknowns.
    model_config = ConfigDict(
        extra="forbid", frozen=True, strict=True, revalidate_instances="always"
    )

    @classmethod
    def model_json_schema(cls, *args: Any, **kwargs: Any) -> dict[str, Any]:
        """Keep legacy-only account envelopes on their original wire contract."""
        from ._legacy import preserve_legacy_account_schema

        schema = super().model_json_schema(*args, **kwargs)
        ref_template = args[1] if len(args) > 1 else kwargs.get("ref_template", "#/$defs/{model}")
        return preserve_legacy_account_schema(schema, ref_template)


def _validate_instance[T: Model](model_type: type[T], value: T) -> T:
    """Check Python types first, then JSON/UTF-8 representability before consumption or I/O."""
    checked = model_type.model_validate(value)
    canonical(checked.model_dump(mode="json")).encode("utf-8")
    return checked


class Identified(Model):
    """Content-derived identities refuse caller substitutions on readback."""

    namespace: ClassVar[str] = ""
    id: str = ""

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if "namespace" not in cls.__dict__ or not cls.namespace:
            raise TypeError("identified subclasses require an explicit stable namespace")

    def identity_fields(self) -> dict[str, Any]:
        return self.model_dump(mode="json", exclude={"id"})

    @model_validator(mode="after")
    def identify(self) -> Self:
        expected = self.namespace + ":" + digest(self.identity_fields())
        if self.id and self.id != expected:
            raise ValueError("identity differs from content")
        object.__setattr__(self, "id", expected)
        return self
