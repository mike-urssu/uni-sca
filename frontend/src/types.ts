// Mirrors backend sca.model / sca.job JSON (Kotlin Pair serializes as {first, second}).
export type Status = 'QUEUED' | 'RUNNING' | 'DONE' | 'PARTIAL' | 'FAILED'
export interface Sourced<T> { value: T; source: string; evidence?: string | null }
export interface Hash { alg: string; content: string }
export interface Conflict { field: string; value: string; source: string }
export interface Edge { first: string; second: string }

export interface Component {
  key: string; purl: string | null; name: string; version: string | null; ecosystem: string
  qualifiers: Record<string, string>
  licenses: Sourced<string> | null; hashes: Sourced<Hash>[]; scope: Sourced<string> | null
  downloadUrl: Sourced<string> | null; registry: Sourced<string> | null; supplier: Sourced<string> | null
  sourceRepo: Sourced<string> | null; locations: string[]; foundBy: string[]; direct: boolean
  copyright: Sourced<string> | null   // one statement per line; source "manual" when entered by hand
  conflicts: Conflict[]; unresolved: string[]; needsReview: boolean; licenseTexts: Record<string, string>
}

export interface ToolRun { tool: string; image: string; ok: boolean; millis: number; log: string }
export interface Warning { code: string; message: string }
export type Kind = 'REPO' | 'IMAGE'
export interface TargetInfo { kind: Kind | 'PRODUCT'; input: string; ref: string | null; id: string | null }

export interface ScanResult {
  target: TargetInfo; status: Status; tools: ToolRun[]; components: Component[]
  edges: Edge[]; roots: string[]; warnings: Warning[]; createdAt: string
}

export interface ScanInput {
  target: string; ref: string | null; deep: boolean
  productId: string | null; versionId: string | null; sourceId: string | null
}
export interface JobStatus {
  id: string; status: Status; stage: string | null; input: ScanInput; createdAt: string
  tools: ToolRun[]; error: string | null
}

export const TERMINAL: Status[] = ['DONE', 'PARTIAL', 'FAILED']

export interface Source { id: string; kind: Kind; target: string; ref: string | null; deep: boolean }
export interface SourceInput { kind: Kind; target: string; ref?: string; deep: boolean }
export interface ProductVersion { id: string; version: string; createdAt: string; sources: Source[] }
export interface Product { id: string; name: string; createdAt: string; versions: ProductVersion[] }

export type Change = 'ADDED' | 'REMOVED' | 'VERSION_CHANGED' | 'LICENSE_CHANGED'
export interface DiffRow {
  id: string; name: string; ecosystem: string; change: Change
  before: string[]; after: string[]; licenseBefore: string | null; licenseAfter: string | null
}
