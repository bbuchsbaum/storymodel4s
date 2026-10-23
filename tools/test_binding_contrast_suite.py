"""Paired binding contrasts for the connected assessor (bead bd-01M5MR2N8NN2XZRH6FBWBC1EYT).

Each pair holds a case the assessor must credit beside a same-shape case it must resist. Proposals
are hand-written stand-ins for a model. Fixtures are authored; they contain no story text, recall
transcript or excerpt. A case the current validator cannot yet decide is an expectedFailure naming
the slice that owns it, so landing that slice turns it into an unexpected success to remove.
"""
import unittest

from test_connected_assessment_probe import (
    add_source,
    assess,
    connect,
    fixture,
    statuses,
    timed,
)


def world(core):
    return dict(
        core=core,
        polarity="positive",
        scopeNote="Complete predicate in stated scope.",
        mode="world",
        exclusivity=None,
    )


def two_people(first, second, cores=("A person is on the floor.", "A person is dead.")):
    """One remembered person, two records; the proof needs an identity edge between them."""
    r, v = fixture()
    r["items"] = [timed("e1", first), timed("e2", second)]
    for c, core in zip(r["inventory"]["components"], cores):
        c["assessment"] = world(core)
    v["citations"][0]["quote"] = first
    proof = add_source(r, v, "e2", "b")
    v["components"][1].update(
        unresolved=[],
        affirms=[
            dict(
                sources=[proof],
                basis=dict(kind="direct", explanation="Explicit state."),
            )
        ],
    )
    connect(v, "same_situation", "as", "bs")
    connect(v, "same_context", "ak", "bk")
    return r, v


def depicted_and_described(dialogue, scope="assertion"):
    """Cross-scope identity: a depicted person and a person spoken about."""
    r, v = fixture()
    r["inventory"].update(logic="atomic", eventPolicy="separate_scopes")
    r["inventory"]["components"] = r["inventory"]["components"][:1]
    c = r["inventory"]["components"][0]
    c["roles"].append(
        dict(id="r2", kind="entity", quote="is dead", argument="described referent")
    )
    c["assessment"].update(
        core="The depicted and described people are the same person.",
        mode="cross_scope_identity",
    )
    r["items"][1] = timed("e2", dialogue)
    v["components"] = v["components"][:1]
    second = add_source(r, v, "e2", "b", scope=scope)
    second["roles"] = {"r2": "bp"}
    v["components"][0]["affirms"][0]["sources"].append(second)
    return r, v


class IdentityAcrossShots(unittest.TestCase):
    def test_succeeds_with_explicit_reference(self):
        r, v = two_people("Ana lies on the floor.", "Ana is dead.")
        connect(v, "same_entity", "ap", "bp", basis="explicit_reference")
        self.assertEqual(statuses(assess(r, v)), ["Supported", "Supported"])

    def test_resists_missing_identity_edge(self):
        r, v = two_people(
            "A woman in pink lies on the floor.", "A woman in pink is dead."
        )
        self.assertEqual(statuses(assess(r, v)), ["Unresolved", "Unresolved"])

    @unittest.expectedFailure
    def test_resists_appearance_only_identity(self):
        # S2: the basis vocabulary cannot say "appearance", so a matching coat passes as identity.
        r, v = two_people(
            "A woman in pink lies on the floor.", "A woman in pink is dead."
        )
        connect(v, "same_entity", "ap", "bp")
        v["connections"][-1]["explanation"] = "Both wear a pink coat."
        self.assertEqual(statuses(assess(r, v)), ["Unresolved", "Unresolved"])


class DialogueReference(unittest.TestCase):
    def test_succeeds_when_dialogue_names_the_depicted_person(self):
        r, v = depicted_and_described(
            "Carl, looking at the woman on the floor, says she is dead."
        )
        connect(v, "same_entity", "ap", "bp", basis="explicit_reference")
        self.assertEqual(statuses(assess(r, v)), ["Supported"])

    def test_resists_dialogue_without_reference(self):
        r, v = depicted_and_described("Carl, on the telephone, says his aunt is dead.")
        self.assertEqual(statuses(assess(r, v)), ["Unresolved"])

    @unittest.expectedFailure
    def test_resists_per_question_identity_claim_about_someone_elsewhere(self):
        # S5: identity must come from the recall-blind source graph, not this proposal's edge.
        r, v = depicted_and_described("Carl, on the telephone, says his aunt is dead.")
        connect(v, "same_entity", "ap", "bp", basis="explicit_reference")
        self.assertEqual(statuses(assess(r, v)), ["Unresolved"])


class StateAcrossTime(unittest.TestCase):
    def setUp(self):
        self.r, self.v = fixture()
        self.r["intervals"] = [dict(part="p", start=152.0, end=155.0)]
        self.r["items"] = [
            timed("e1", "Ana is on the floor.", 110.0, 114.0),
            timed("e2", "Ana stands up.", 130.0, 132.0),
            timed("e3", "Two men stand in the room.", 152.0, 155.0),
        ]
        add_source(self.r, self.v, "e3", "c", roles=False)

    def test_succeeds_with_cited_persistence(self):
        connect(self.v, "same_situation", "as", "cs")
        self.assertEqual(statuses(assess(self.r, self.v)), ["Supported", "Unresolved"])

    def test_resists_earlier_state_without_continuity(self):
        self.assertEqual(statuses(assess(self.r, self.v)), ["Unresolved", "Unresolved"])

    @unittest.expectedFailure
    def test_resists_continuity_across_a_state_change(self):
        # S3: continuity is a per-question proposal; nothing stops it at the stand-up record.
        connect(self.v, "same_situation", "as", "cs")
        self.v["connections"][-1]["explanation"] = "Same room, later shot."
        self.assertEqual(statuses(assess(self.r, self.v)), ["Unresolved", "Unresolved"])


class ModifierVersusIdentity(unittest.TestCase):
    def test_succeeds_on_posture_despite_disputed_gender(self):
        r, v = fixture()
        r["inventory"]["components"][1]["assessment"] = world(
            "The depicted person is a man."
        )
        self.assertEqual(statuses(assess(r, v)), ["Supported", "Unresolved"])

    def test_resists_combining_two_peoples_attributes(self):
        r, v = two_people("Ana lies on the floor.", "Beth is dead.")
        self.assertEqual(statuses(assess(r, v)), ["Unresolved", "Unresolved"])
        self.assertEqual(
            statuses(assess(r, v), "localStatus"), ["Supported", "Supported"]
        )


class ContextualInference(unittest.TestCase):
    def test_resists_promoting_belief_to_world(self):
        r, v = fixture()
        v["handles"][2]["scope"] = "belief"
        self.assertEqual(statuses(assess(r, v)), ["Unresolved", "Unresolved"])

    def test_resists_promoting_assertion_to_world(self):
        r, v = depicted_and_described(
            "Carl, looking at the woman on the floor, says she is dead."
        )
        connect(v, "same_entity", "ap", "bp", basis="explicit_reference")
        r["inventory"]["components"][0]["assessment"] = world(
            "The depicted person is dead."
        )
        self.assertEqual(statuses(assess(r, v)), ["Unresolved"])

    @unittest.expectedFailure
    def test_succeeds_on_uncontested_assertion_as_inference(self):
        # S4: no rule yet carries an uncontested assertion into the narrated world.
        r, v = depicted_and_described(
            "Carl, looking at the woman on the floor, says she is dead."
        )
        connect(v, "same_entity", "ap", "bp", basis="explicit_reference")
        r["inventory"]["components"][0]["assessment"] = world(
            "The depicted person is dead."
        )
        self.assertEqual(statuses(assess(r, v)), ["Supported"])


class ComplementarySources(unittest.TestCase):
    def setUp(self):
        r, v = fixture()
        r["inventory"]["logic"] = "atomic"
        r["inventory"]["components"] = r["inventory"]["components"][:1]
        r["inventory"]["components"][0]["assessment"].update(
            core="A person says that the gate is closed.", mode="assertion"
        )
        r["items"] = [
            timed("e1", "Ana speaks these words: the gate is closed."),
            timed("e2", "The gate is closed."),
        ]
        v.update(
            citations=[], handles=[], connections=[], components=v["components"][:1]
        )
        first = add_source(r, v, "e1", "a", scope="assertion", occurrence="assertion")
        second = add_source(
            r, v, "e2", "b", roles=False, scope="assertion", occurrence="assertion"
        )
        for kind, suffix in [("situation", "s"), ("context", "k")]:
            connect(v, "same_" + kind, "a" + suffix, "b" + suffix)
        v["components"][0]["affirms"] = [
            dict(
                sources=[first, second],
                basis=dict(
                    kind="direct",
                    explanation="Annotation names the speaker; ASR supplies words.",
                ),
            )
        ]
        self.r, self.v = r, v

    def test_succeeds_with_explicit_occurrence_correspondence(self):
        connect(self.v, "same_assertion", "ao", "bo", basis="utterance_correspondence")
        self.assertEqual(statuses(assess(self.r, self.v)), ["Supported"])

    def test_resists_proximity_without_correspondence(self):
        self.assertEqual(statuses(assess(self.r, self.v)), ["Unresolved"])


class Polarity(unittest.TestCase):
    def test_succeeds_on_negated_claim_with_explicit_negation(self):
        r, v = fixture()
        r["inventory"]["components"][0]["assessment"]["polarity"] = "negative"
        r["items"][0] = timed("e1", "Ana is not on the floor.")
        v["citations"][0]["quote"] = "Ana is not on the floor."
        proof = v["components"][0]["affirms"].pop()
        proof["basis"] = dict(
            kind="explicit_negation", explanation="Explicit negation."
        )
        v["components"][0]["denies"] = [proof]
        self.assertEqual(statuses(assess(r, v)), ["Supported", "Unresolved"])

    def test_resists_compatible_detail_as_negation(self):
        r, v = fixture()
        r["inventory"]["components"][0]["assessment"].update(
            core="The object is speckled.", polarity="negative"
        )
        v["components"][0]["denies"] = v["components"][0]["affirms"]
        v["components"][0]["affirms"] = []
        with self.assertRaisesRegex(
            ValueError, "denial needs positive incompatibility"
        ):
            assess(r, v)


if __name__ == "__main__":
    unittest.main()
