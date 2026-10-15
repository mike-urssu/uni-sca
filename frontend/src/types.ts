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
  conflicts: Conflict[]; unresolved: string[]; needsReview: boolean; licenseTexts: Record<string, string>
}

export interface ToolRun { tool: string; image: string; ok: boolean; millis: number; log: string }
export interface Warning { code: string; message: string }
export interface TargetInfo { kind: 'REPO' | 'IMAGE'; input: string; ref: string | null; id: string | null }

export interface ScanResult {
  target: TargetInfo; status: Status; tools: ToolRun[]; components: Component[]
  edges: Edge[]; roots: string[]; warnings: Warning[]; createdAt: string
}

export interface ScanInput { target: string; ref: string | null; deep: boolean }
export interface JobStatus {
  id: string; status: Status; stage: string | null; input: ScanInput; createdAt: string
  tools: ToolRun[]; error: string | null
}

export const TERMINAL: Status[] = ['DONE', 'PARTIAL', 'FAILED']
