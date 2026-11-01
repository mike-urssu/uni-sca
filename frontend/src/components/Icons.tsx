// Stroke icons (24px grid) that inherit the text color; the button around them carries the accessible label.
const svg = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const

export const TrashIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M10 11v6M14 11v6" /></svg>
export const RefreshIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="M21 12a9 9 0 0 1-15.5 6.2L3 16M3 12a9 9 0 0 1 15.5-6.2L21 8M21 3v5h-5M3 21v-5h5" /></svg>
export const PencilIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="M17 3a2.83 2.83 0 0 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" /></svg>
export const CheckIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="M20 6 9 17l-5-5" /></svg>
export const XIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="M18 6 6 18M6 6l12 12" /></svg>
export const AlertIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><circle cx="12" cy="12" r="10" /><path d="M12 8v4M12 16h.01" /></svg>
export const ChevronIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="m9 6 6 6-6 6" /></svg>
export const InfoIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><circle cx="12" cy="12" r="10" /><path d="M12 16v-4M12 8h.01" /></svg>
export const DownloadIcon = ({ size = 18 }: { size?: number }) => <svg {...svg} width={size} height={size}><path d="M12 3v12M7 10l5 5 5-5M5 21h14" /></svg>
