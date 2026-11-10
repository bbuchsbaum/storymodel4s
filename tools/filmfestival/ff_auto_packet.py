"""The film record and prompts of the automatic-input and no-record conditions.

Protocol: docs/plans/2026-11-10-filmfestival-automatic-input-protocol.md.

The automatic record represents each film by what can be derived from its video: the non-suspect
speech of the Whisper transcript and one model-written description per sampled frame, in time
order, with times from the start of the film's clip. It holds no scene number, no row number and
no human-written description. The no-record packet holds the film headers and nothing else.

One thing is taken from the annotation: the film titles, read from its film names exactly as the
annotation-assisted packet reads them. Both conditions show them, with the film numbers and the
clip lengths.

Stated differences from the annotation-assisted prompt (`ff_packet.SYSTEM`). The introduction, the
two judgements and their definitions are the same words. The description of the input differs.
The three kinds of answer are the same three, with a time where that prompt has a scene number.
The reply has no `alts` and no `rows`: there are no scene numbers or rows to cite.
"""

import json

import ff_caption
import ff_frames
import ff_gold
import ff_packet

HEADING = {"auto": "FILM RECORD", "norecord": "FILMS"}
NO_SPEECH = "(this film has no speech)"
NO_RECORD = "(no record of this film is provided)"
NO_DESCRIPTION = "[no description]"

_INTRO = """You are an expert annotator for a memory study. Participants watched ten short films \
in one sitting (five in a first block, five in a second; each film opened with a title card) and \
later freely recalled them aloud, in any order. You map recall onto the films."""

_INPUT_AUTO = """You get (1) an automatic, time-stamped record of all ten films. For each film it \
lists, in time order, the automatically transcribed speech (in quotation marks) and automatic \
one-sentence descriptions of single frames taken every 2 seconds (marked "(frame)"). Times are \
minutes:seconds from the start of that film; the record of a film starts after its title card. \
There are no human annotations and no scene divisions. The frame descriptions were written \
without knowing the film, so they describe people by appearance and do not name them. Some films \
have no speech. And (2) one participant's recall from one session, split into numbered units."""

_INPUT_NORECORD = """You get (1) a list of the ten films: number, title and length. No record of \
what happens in the films is provided. Use only what you know of these films. Times are \
minutes:seconds from the start of a film, which starts after its title card. And (2) one \
participant's recall from one session, split into numbered units."""

_JUDGEMENTS = """For every target unit, give TWO judgements, because they can differ:

1. "narrative": the scene the participant's retelling is currently at, i.e. which part of which \
film they are narrating at this point. Commentary, hesitation, evaluation and explanations belong \
to the scene currently being narrated. When the participant briefly cites an earlier or later \
event to explain what is happening now ("because earlier he had said..."), the narrative scene is \
still the current one. When they genuinely jump back or forward, or move to another film, the \
narrative scene follows them.
2. "referent": the scene where the specific event, fact or line this unit mentions actually \
happens on screen. This often equals the narrative scene but differs for flashbacks, \
explanations, anticipations and summaries."""

_ANSWERS = """Each judgement is one of three kinds:
- A time in a film: give the film number and a time within the scene, as "m:ss" from the start \
of that film. Use this only when the unit points to a particular scene.
- A film without a particular scene: when the unit is about a film as a whole (naming it or its \
title, a general summary or opinion of it, saying which film comes next), give the film number \
and null for the time. Do not invent a time to be more precise than the unit is.
- Nothing: when the unit refers to none of the ten films (searching memory, giving up, talking \
about something else), give null for both the time and the film.

Also give "conf" 0-1.
Distinguish similar moments carefully: several films show the same person or place repeatedly. \
Match the specific moment.

Reply with JSON only: {"units": [{"u": <unit number>, "narrative_film": <1-10 or null>, \
"narrative_time": <"m:ss" or null>, "referent_film": <1-10 or null>, "referent_time": <"m:ss" or \
null>, "conf": <0-1>}, ...]} covering every target unit exactly once."""

SYSTEM_AUTO = "\n\n".join([_INTRO, _INPUT_AUTO, _JUDGEMENTS, _ANSWERS])
SYSTEM_NORECORD = "\n\n".join([_INTRO, _INPUT_NORECORD, _JUDGEMENTS, _ANSWERS])
SYSTEM = {"auto": SYSTEM_AUTO, "norecord": SYSTEM_NORECORD}


def length(seconds):
    """A clip length as m:ss, rounded down so that it is never past the end of the clip."""
    whole = int(seconds)
    return f"{whole // 60}:{whole % 60:02d}"


def titles(annotation_path):
    """{film code: title}, as the annotation-assisted packet reads them."""
    film_of, _ = ff_gold.load_dictionary(annotation_path)
    out = {}
    for r in ff_gold.read_tsv(annotation_path):
        film = film_of[int(r["scene_number"])]
        if film != ff_gold.CARTOON and film not in out:
            out[film] = r["film"].split(". ", 1)[1].strip()
    return out


def read_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def frame_list(frames_path):
    """The frame list, refused unless it lists the ten films in order."""
    doc = read_json(frames_path)
    if doc.get("schema") != ff_frames.SCHEMA or (
        [entry["film"] for entry in doc["films"]] != list(ff_gold.FILMS)
    ):
        raise ff_gold.GoldError("the frame list does not list the ten films")
    return doc


def headers(annotation_path, frames_doc):
    """{film code: header line}: number, title and clip length."""
    named = titles(annotation_path)
    return {
        entry["film"]: (
            f"##### FILM {entry['film']}: {named[entry['film']]} "
            f"({length(entry['clipSeconds'])} long)"
        )
        for entry in frames_doc["films"]
    }


def descriptions(frames_path, frames_doc, captions_path):
    """{film code: [(seconds, description or None), ...]}, checked against the frame list.

    Refuses captions made for another frame list, by another model or instruction, or with more
    undescribed frames than the protocol allows.
    """
    captions = read_json(captions_path)
    expected = {
        "schema": ff_caption.CAPTIONS_SCHEMA,
        "model": ff_caption.MODEL,
        "instructionSha256": ff_packet.sha(ff_caption.SYSTEM),
        "framesSha256": ff_frames.file_sha(frames_path),
    }
    for name, value in expected.items():
        if captions.get(name) != value:
            raise ff_gold.GoldError(f"the frame descriptions have another {name}")
    out = {}
    for entry in frames_doc["films"]:
        rows = captions["films"].get(str(entry["film"]), [])
        listed = [(f["seconds"], f["sha256"]) for f in entry["frames"]]
        if [(r["seconds"], r["sha256"]) for r in rows] != listed:
            raise ff_gold.GoldError(
                f"film {entry['film']}: the descriptions are not of the listed frames"
            )
        texts = [r["description"] for r in rows]
        if any(t is not None and not isinstance(t, str) for t in texts):
            raise ff_gold.GoldError(f"film {entry['film']}: a description is not text")
        if texts.count(None) > ff_caption.MAX_UNDESCRIBED * len(texts):
            raise ff_gold.GoldError(
                f"film {entry['film']}: too many frames are undescribed"
            )
        out[entry["film"]] = [(r["seconds"], r["description"]) for r in rows]
    if set(captions["films"]) != {str(film) for film in out}:
        raise ff_gold.GoldError("the frame descriptions list another film")
    return out


def speech(transcription_root, entry):
    """A clip's speech, refused unless the transcript was made from the clip the frames are of."""
    path = f"{transcription_root}/{entry['cover']}/transcript.json"
    if read_json(path).get("source", {}).get("sha256") != entry["clipSha256"]:
        raise ff_gold.GoldError(
            f"film {entry['film']}: the transcript and the frames are of different clips"
        )
    return ff_packet.speech(path)


def render_auto(annotation_path, transcription_root, frames_path, captions_path):
    """The automatic film record: per film a header, then speech and frames in time order."""
    frames_doc = frame_list(frames_path)
    head = headers(annotation_path, frames_doc)
    described = descriptions(frames_path, frames_doc, captions_path)
    blocks = []
    for entry in frames_doc["films"]:
        film = entry["film"]
        events = []
        said = speech(transcription_root, entry)
        for at, text in said:
            events.append((at, 0, f'{ff_packet.clock(at)} "{text}"'))
        for at, text in described[film]:
            shown = NO_DESCRIPTION if text is None else " ".join(text.split())
            events.append((at, 1, f"{ff_packet.clock(at)} (frame) {shown}"))
        lines = [head[film]] + ([] if said else [NO_SPEECH])
        # A stable sort: at one time, speech comes before the frame.
        lines += [line for _, _, line in sorted(events, key=lambda e: e[:2])]
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def render_norecord(annotation_path, frames_path):
    """The no-record packet: each film's header and a line saying there is no record."""
    frames_doc = frame_list(frames_path)
    head = headers(annotation_path, frames_doc)
    return "\n".join(f"{head[e['film']]}\n{NO_RECORD}" for e in frames_doc["films"])
