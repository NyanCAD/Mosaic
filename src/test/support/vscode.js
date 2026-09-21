// Minimal VS Code API stand-in for the node-test build.
//
// Only the surface the extension host touches while loading and while
// resolving/reading files is implemented. `workspace.fs.readFile` returns the
// resolved URI path so tests can assert exactly which path the message router
// produced, without depending on a real filesystem.
//
// @tags on-demand symbol generation

/** @tags on-demand symbol generation */
export class Uri {
  /** @tags on-demand symbol generation */
  constructor(path) {
    this.path = path;
    this.scheme = "file";
  }

  /** @tags on-demand symbol generation */
  static file(path) {
    return new Uri(String(path));
  }

  /** @tags on-demand symbol generation */
  static parse(value) {
    return new Uri(String(value).replace(/^file:\/\//, ""));
  }

  /** @tags on-demand symbol generation */
  static joinPath(base, ...segments) {
    const parts = [base.path, ...segments].filter((part) => part !== "");
    return new Uri(parts.join("/").replace(/\/{2,}/g, "/"));
  }

  /** @tags on-demand symbol generation */
  toString() {
    return `file://${this.path}`;
  }
}

const noopDisposable = { dispose() {} };

/** @tags on-demand symbol generation */
export const workspace = {
  workspaceFolders: [{ uri: new Uri("/workspace") }],
  fs: {
    /** @tags on-demand symbol generation */
    async readFile(uri) {
      return new TextEncoder().encode(`content:${uri.path}`);
    },
    /** @tags on-demand symbol generation */
    async writeFile() {},
    /** @tags on-demand symbol generation */
    async stat(uri) {
      return { type: 1, uri };
    },
  },
  /** @tags on-demand symbol generation */
  async applyEdit() {
    return true;
  },
  /** @tags on-demand symbol generation */
  async openTextDocument() {
    return {};
  },
  /** @tags on-demand symbol generation */
  onDidChangeTextDocument() {
    return noopDisposable;
  },
};

/** @tags on-demand symbol generation */
export class Position {
  /** @tags on-demand symbol generation */
  constructor(line, character) {
    this.line = line;
    this.character = character;
  }
}

/** @tags on-demand symbol generation */
export class Range {
  /** @tags on-demand symbol generation */
  constructor(start, end) {
    this.start = start;
    this.end = end;
  }
}

/** @tags on-demand symbol generation */
export class WorkspaceEdit {
  /** @tags on-demand symbol generation */
  replace() {}
}

/** @tags on-demand symbol generation */
export const commands = {
  /** @tags on-demand symbol generation */
  async executeCommand() {},
  /** @tags on-demand symbol generation */
  registerCommand() {
    return noopDisposable;
  },
};

/** @tags on-demand symbol generation */
export const window = {
  /** @tags on-demand symbol generation */
  createOutputChannel() {
    return { appendLine() {}, show() {}, dispose() {} };
  },
  /** @tags on-demand symbol generation */
  registerCustomEditorProvider() {
    return noopDisposable;
  },
  activeTextEditor: undefined,
};
