# Terminal protocol responses

Interactive programs can query terminal state before they begin accepting user answers. A terminal must return those reports through the same session's PTY input. Rendering the prompt without replying can leave the program waiting indefinitely.

Supported requests:

- `CSI 5 n`: operating status, answered with `CSI 0 n`
- `CSI 6 n`: cursor position, answered with `CSI row ; column R`
- `CSI ? 6 n`: DEC cursor position, answered with `CSI ? row ; column R`

Rows and columns are one-based. Origin mode reports the row relative to the scrolling region. A pending autowrap reports the last visible column. Requests with unsupported parameters or intermediate bytes do not generate a reply.

`AnsiTerminalEmulator.parseWithResponses` captures each reply when its query is parsed, so multiple queries in one output chunk retain their own positions. Incomplete sequences remain buffered by the existing ANSI scanner. `parse` remains available for consumers that only render output.

`OutputProcessor` attaches the source session ID to each reply. `TerminalManager` writes it using that session's serialized input queue. A protocol reply does not clear the waiting-for-user-input flag, complete a command, consume the command queue, apply keyboard modifiers, or append an Enter key.

GitHub CLI 2.45.0 uses Survey 2.3.7. Survey's confirmation reader hides the cursor and queries terminal dimensions and cursor location before reading the answer. These reports repair that protocol exchange for Survey and other programs that use the same requests; ordinary input prompts that do not send these queries do not depend on this feature.

Regression sources cover split sequences, reply ordering, origin mode, pending autowrap, the Survey size probe, source-session routing, and preservation of the waiting prompt. Runtime validation and device checks must be recorded separately from the presence of test sources. Android keyboard focus and IME visibility remain separate from terminal protocol replies.
