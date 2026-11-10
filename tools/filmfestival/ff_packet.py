"""The FilmFestival film packet and prompts for the transfer evaluation.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md.

One packet holds all ten films, so a call is never told which film an utterance is about. Scene
ids are the release's global ids (1-216); row ids are the annotation's segment numbers. Speech is
listed per film, apart from the rows: the annotation and media clocks differ by a few seconds, so
a line of dialogue is not presented as belonging to a particular row. The lobby cartoon is left
out. Title cards are shown and marked; they are not scene answers.
"""

import hashlib
import json

import ff_gold

# Film code (as in the reference labels) to the clip directory of its transcription.
CLIPS = {
    1: "catch_me_if_you_can",
    2: "the_record",
    3: "the_boyfriend",
    4: "the_shoe",
    5: "keith_reynolds",
    6: "the_rock",
    7: "the_prisoner",
    8: "the_black_hole",
    9: "post_it_love",
    10: "bus_stop",
}

SYSTEM = """You are an expert annotator for a memory study. Participants watched ten short films \
in one sitting (five in a first block, five in a second; each film opened with a title card) and \
later freely recalled them aloud, in any order. You map recall onto the films.

You get (1) a timed annotation of all ten films. Each film is divided into numbered scenes. Scene \
numbers run on across the films, so a scene number identifies its film. Each scene has one or \
more described moments (rows rNNN) with times from the start of that film. After a film's scenes \
comes its automatically transcribed dialogue as a separate list; it is not aligned to the rows, \
and some films have no speech. And (2) one participant's recall from one session, split into \
numbered units.

For every target unit, give TWO judgements, because they can differ:

1. "narrative": the scene the participant's retelling is currently at, i.e. which part of which \
film they are narrating at this point. Commentary, hesitation, evaluation and explanations belong \
to the scene currently being narrated. When the participant briefly cites an earlier or later \
event to explain what is happening now ("because earlier he had said..."), the narrative scene is \
still the current one. When they genuinely jump back or forward, or move to another film, the \
narrative scene follows them.
2. "referent": the scene where the specific event, fact or line this unit mentions actually \
happens on screen. This often equals the narrative scene but differs for flashbacks, \
explanations, anticipations and summaries.

Each judgement is one of three kinds:
- A scene: give its scene number and its film. Use this only when the unit points to a \
particular scene.
- A film without a particular scene: when the unit is about a film as a whole (naming it or its \
title, a general summary or opinion of it, saying which film comes next), give the film number \
and null for the scene. Scenes marked [title card] are not scenes you can choose. Do not invent \
a scene to be more precise than the unit is.
- Nothing: when the unit refers to none of the ten films (searching memory, giving up, talking \
about something else), give null for both the scene and the film.

Also give "alts": up to 3 candidate narrative scenes with probabilities summing to at most 1 \
(include your chosen narrative scene if you chose one), up to 3 supporting "rows" (integers), and \
"conf" 0-1.
Distinguish similar moments carefully: several films show the same person or place repeatedly. \
Match the specific moment.

Reply with JSON only: {"units": [{"u": <unit number>, "narrative": <scene number or null>, \
"narrative_film": <1-10 or null>, "referent": <scene number or null>, "referent_film": <1-10 or \
null>, "alts": [[scene, p], ...], "rows": [..], "conf": <0-1>}, ...]} covering every target unit \
exactly once."""


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def clock(seconds):
    whole = int(round(seconds))
    return f"{whole // 60}:{whole % 60:02d}"


def speech(transcript_path):
    """Non-suspect transcript segments of one clip, in order: (start seconds, text)."""
    with open(transcript_path, encoding="utf-8") as handle:
        doc = json.load(handle)
    if doc.get("outcome") != "complete":
        raise ff_gold.GoldError(f"transcription did not complete: {transcript_path}")
    out = []
    for segment in doc["segments"]:
        text = segment["text"].strip()
        if segment["suspect"] is True or not text:
            continue
        out.append((float(segment["start_seconds"]), text))
    return out


def render(annotation_path, transcription_root):
    """The packet text. Films in presentation order with their reference film codes."""
    rows = ff_gold.read_tsv(annotation_path)
    film_of, valid = ff_gold.load_dictionary(annotation_path)
    films = {}
    for r in rows:
        film = film_of[int(r["scene_number"])]
        if film != ff_gold.CARTOON:
            films.setdefault(film, []).append(r)
    blocks = []
    for film in sorted(films):
        own = films[film]
        title = own[0]["film"].split(". ", 1)[1].strip()
        start = float(own[0]["start_s"])
        scenes = sorted({int(r["scene_number"]) for r in own})
        lines = [f"##### FILM {film}: {title} (scenes {scenes[0]}-{scenes[-1]})"]
        for scene in scenes:
            mark = "" if scene in valid else " [title card]"
            lines.append(f"=== SCENE {scene}{mark}")
            for r in own:
                if int(r["scene_number"]) == scene:
                    at = clock(float(r["start_s"]) - start)
                    lines.append(
                        f"r{r['segment']} {at} {' '.join(r['description'].split())}"
                    )
        said = speech(f"{transcription_root}/{CLIPS[film]}/transcript.json")
        if said:
            lines.append(
                f"--- FILM {film} dialogue (automatic transcription, not aligned to rows)"
            )
            lines.extend(f"{clock(at)} {text}" for at, text in said)
        else:
            lines.append(f"--- FILM {film} has no speech")
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def units(gold_path, participant, session):
    """One participant-session's utterances in order: (unit number, gold row, text)."""
    rows = [
        r
        for r in ff_gold.read_tsv(gold_path)
        if r["participant"] == participant and r["run"] == session
    ]
    rows.sort(key=lambda r: int(r["gold_row"]))
    return [
        (i + 1, int(r["gold_row"]), " ".join(r["text"].split()))
        for i, r in enumerate(rows)
    ]


def prompt(packet, session_units, lo, hi, heading="FILM ANNOTATION"):
    """The user prompt for target units `lo..hi` (unit numbers, inclusive).

    `heading` names the film packet; it is the only part that differs between input conditions.
    """
    recall = "\n".join(f"[{u}] {text}" for u, _, text in session_units)
    return (
        f"{heading}\n{packet}\n\nPARTICIPANT RECALL (all units)\n{recall}\n\n"
        f"TARGET UNITS: map every unit numbered {lo} through {hi} inclusive."
    )


def chunks(packet, session_units, size, heading="FILM ANNOTATION"):
    """Every chunk's (lo, hi, prompt), `size` units at a time."""
    out = []
    for i in range(0, len(session_units), size):
        group = session_units[i : i + size]
        lo, hi = group[0][0], group[-1][0]
        out.append((lo, hi, prompt(packet, session_units, lo, hi, heading)))
    return out
