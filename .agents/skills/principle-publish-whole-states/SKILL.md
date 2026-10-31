---
name: principle-publish-whole-states
description: "Apply when writing code that changes observable state across async work: sign-in, loading, navigation, anything a UI or animation reacts to. Keep fields that change together in one value, tell 'not loaded' from 'empty', fetch before you publish, and derive instead of fixing up later."
---

# Publish Whole States

A UI shows every state you publish, including the ones in the middle of an update. A state that is only half set is a real screen to the user. An animation makes it worse: a wrong frame that would flash for a moment is now a 400 ms transition between two wrong screens.

**Example:** signing in set `server`, then suspended to fetch `realms`. Compose drew a frame in between: a server with no realms, so it showed the empty overview. When the realms arrived, `loadRealms` opened the first one, and the overview zoomed into it. Nothing in the UI was wrong. It drew a state that `Home` should never have published.

- **Make the half-set state unrepresentable.** Fields that must change together belong in one value that you assign once. Use `var account: Account?` with the API, realms, and session inside, not `server`, `realms`, and `session` as three fields. Then the compiler rejects "a server without its realms". See the **principle-type-system-discipline** skill.
- **Tell "not loaded" from "empty".** An empty list can't say whether it was fetched. Use `null`, or a sealed `Loading`/`Loaded(list)`, for data that hasn't come back yet. The UI then waits, or shows nothing, instead of drawing "you have no realms" and changing its mind a moment later.
- **Fetch, then publish.** Do every suspending call first, into locals. Then make all the writes together with no suspension point in between. Compose doesn't draw in the middle of plain code, only at suspension points, so a block with no `await` lands in one frame. A write before an `await` is visible on its own.
- **Derive, don't fix up later.** Code like "when the realms load, if nothing is open, open the first one" changes the screen at some unknown later time, after the UI has already drawn something. Compute the dependent value from its inputs, or set it in the same write as them.
- **An animation nobody asked for is a bug report.** If a transition runs and the user did nothing to start it, find which intermediate state started it. Don't silence the animation. That hides the half-set state, and the next screen to react to it will show it again.
- **Fixing the state isn't the end: give the change a transition you chose.** Removing the wrong animation often leaves a hard cut, which is a regression too. Pick the transition the change deserves, such as a fade through from setup into a realm.
- **Prove an animation with frames.** Render a filmstrip of the real flow (`./gradlew :desktopApp:preview` writes `login-N`, `zoom-N`, `create-N`, and others to `desktopApp/build/preview/`) and look at every frame, not just the last. A one-frame flash of the wrong screen only shows up there.
- **Test the states the UI sees, not only the last one.** Collect a `snapshotFlow { … }` of what the screen depends on during the operation. Assert that no state in the sequence is one the user shouldn't see. The final state is often right even when the path to it isn't. See the **principle-test-behavior-not-implementation** skill.

**The check:** for each write to observable state, ask "if a frame were drawn right after this line, would it be a screen I'd ship?" If not, move the write next to the others, or put the fields together in one value.
