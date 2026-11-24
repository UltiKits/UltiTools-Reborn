/**
 * Framework-owned item conservation, contribution accounting and bounded container lifecycle.
 *
 * <h2>Gesture callback boundaries</h2>
 * Transfer policies and refusal notifiers must not modify the viewer's inventory or cursor. A gesture
 * snapshots the viewer's items before consulting those callbacks and publishes that snapshot after
 * applying its conserved transfers; inventory edits made by a callback would be overwritten.
 *
 * <h2>Backend implementor requirements</h2>
 * Implementations of {@link com.ultikits.ultitools.ui.ViewerItems} must not throw while writing the cursor
 * or inventory slots. Container changes and contribution accounting have already been applied when
 * viewer items are published. Publication is not rolled back if a write throws, so item-state guarantees
 * do not apply to such an implementation. These requirements belong to the backend, not transfer policies.
 */
@org.jetbrains.annotations.NotNullByDefault
@org.jetbrains.annotations.ApiStatus.Internal
package com.ultikits.ultitools.ui.container;
