import type { VaultCategory } from '../types';

export type VectorIconName =
  | 'allItems'
  | 'favorites'
  | 'starFilled'
  | 'expiring'
  | 'paymentCard'
  | 'bankAccount'
  | 'insuranceCard'
  | 'membershipCard'
  | 'identity'
  | 'officialDocument'
  | 'secureNote'
  | 'settings'
  | 'plus'
  | 'chevronDown'
  | 'chevronRight'
  | 'arrowLeft'
  | 'search'
  | 'edit'
  | 'copy'
  | 'check'
  | 'trash'
  | 'close'
  | 'pin'
  | 'duplicate'
  | 'eye'
  | 'eyeOff'
  | 'present'
  | 'flipCard'
  | 'refresh'
  | 'download'
  | 'upload'
  | 'print'
  | 'camera'
  | 'qrCode'
  | 'globe'
  | 'shield'
  | 'key'
  | 'sparkles'
  | 'sun'
  | 'moon'
  | 'contrast'
  | 'moreVertical'
  | 'paperclip'
  | 'fileText'
  | 'cloud'
  | 'database'
  | 'alertTriangle'
  | 'clock'
  | 'contactless';

const PATHS: Record<VectorIconName, string> = {
  allItems:
    '<rect x="3" y="4" width="7.5" height="7" rx="1.8"/><rect x="13.5" y="4" width="7.5" height="7" rx="1.8"/><rect x="3" y="13" width="7.5" height="7" rx="1.8"/><rect x="13.5" y="13" width="7.5" height="7" rx="1.8"/>',
  favorites:
    '<polygon points="12 2.8 14.9 8.7 21.4 9.6 16.7 14.2 17.8 20.6 12 17.6 6.2 20.6 7.3 14.2 2.6 9.6 9.1 8.7 12 2.8"/>',
  starFilled:
    '<polygon fill="currentColor" points="12 2.8 14.9 8.7 21.4 9.6 16.7 14.2 17.8 20.6 12 17.6 6.2 20.6 7.3 14.2 2.6 9.6 9.1 8.7 12 2.8"/>',
  expiring:
    '<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/>',
  paymentCard:
    '<rect x="2.5" y="5" width="19" height="14" rx="2.6"/><line x1="2.5" y1="10" x2="21.5" y2="10"/><line x1="6.5" y1="15" x2="10.5" y2="15"/>',
  bankAccount:
    '<path d="M3 10h18"/><path d="M12 3 3 8v2h18V8l-9-5Z"/><line x1="6" y1="10" x2="6" y2="18"/><line x1="10" y1="10" x2="10" y2="18"/><line x1="14" y1="10" x2="14" y2="18"/><line x1="18" y1="10" x2="18" y2="18"/><line x1="3" y1="20" x2="21" y2="20"/>',
  insuranceCard:
    '<path d="M12 3 4.5 6.2v5.4c0 5 3.2 8.6 7.5 9.9 4.3-1.3 7.5-4.9 7.5-9.9V6.2L12 3Z"/><line x1="12" y1="8.5" x2="12" y2="14.5"/><line x1="9" y1="11.5" x2="15" y2="11.5"/>',
  membershipCard:
    '<rect x="2.5" y="5" width="19" height="14" rx="2.6"/><line x1="6.5" y1="9" x2="6.5" y2="15"/><line x1="9.5" y1="9" x2="9.5" y2="15"/><line x1="12" y1="9" x2="12" y2="15"/><line x1="15" y1="9" x2="15" y2="15"/><line x1="17.5" y1="9" x2="17.5" y2="15"/>',
  identity:
    '<rect x="2.5" y="4.5" width="19" height="15" rx="2.6"/><circle cx="8.5" cy="11" r="2.3"/><path d="M5.5 16.5c.6-1.7 1.7-2.5 3-2.5s2.4.8 3 2.5"/><line x1="14" y1="9.5" x2="18.5" y2="9.5"/><line x1="14" y1="13" x2="17.5" y2="13"/>',
  officialDocument:
    '<rect x="4" y="2.5" width="16" height="19" rx="2.4"/><circle cx="12" cy="10" r="3.2"/><line x1="8.8" y1="10" x2="15.2" y2="10"/><line x1="8" y1="16.5" x2="16" y2="16.5"/>',
  secureNote:
    '<path d="M14 2.5H6.5A2.5 2.5 0 0 0 4 5v14a2.5 2.5 0 0 0 2.5 2.5h11A2.5 2.5 0 0 0 20 19V8.5L14 2.5Z"/><polyline points="14 2.5 14 8.5 20 8.5"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="16.8" x2="13.5" y2="16.8"/>',
  settings:
    '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.08a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.08a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/>',
  plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  chevronDown: '<polyline points="6 9 12 15 18 9"/>',
  chevronRight: '<polyline points="9 6 15 12 9 18"/>',
  arrowLeft:
    '<line x1="19" y1="12" x2="5" y2="12"/><polyline points="11 18 5 12 11 6"/>',
  search:
    '<circle cx="11" cy="11" r="7"/><line x1="20" y1="20" x2="16.2" y2="16.2"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5Z"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2.2"/><path d="M5 15H4.2A2.2 2.2 0 0 1 2 12.8V4.2A2.2 2.2 0 0 1 4.2 2h8.6A2.2 2.2 0 0 1 15 4.2V5"/>',
  check: '<polyline points="20 6 9 17 4 12"/>',
  trash:
    '<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>',
  close:
    '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  pin: '<path d="M12 17v5"/><path d="M9 3h6l1 6 2.5 2.5v1.5h-13v-1.5L8 9l1-6Z"/>',
  duplicate:
    '<rect x="8" y="8" width="13" height="13" rx="2.2"/><path d="M4 16V5.2A2.2 2.2 0 0 1 6.2 3H16"/>',
  eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
  eyeOff:
    '<path d="M17.94 17.94A10.1 10.1 0 0 1 12 19c-6.4 0-10-7-10-7a18.4 18.4 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.4 0 10 7 10 7a18.5 18.5 0 0 1-2.16 3.19"/><line x1="2" y1="2" x2="22" y2="22"/>',
  present:
    '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>',
  flipCard:
    '<path d="M17 2.5 21 6.5l-4 4"/><path d="M3 11.5v-1a4 4 0 0 1 4-4h14"/><path d="M7 21.5 3 17.5l4-4"/><path d="M21 12.5v1a4 4 0 0 1-4 4H3"/>',
  refresh:
    '<path d="M21 12a9 9 0 0 0-15.5-6.36L3 8"/><polyline points="3 3 3 8 8 8"/><path d="M3 12a9 9 0 0 0 15.5 6.36L21 16"/><polyline points="16 16 21 16 21 21"/>',
  download:
    '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
  upload:
    '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>',
  print:
    '<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/>',
  camera:
    '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
  qrCode:
    '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3z"/><path d="M18 18h3v3h-3z"/><path d="M18 14h3"/><path d="M14 18v3"/>',
  globe:
    '<circle cx="12" cy="12" r="9.5"/><line x1="2.5" y1="12" x2="21.5" y2="12"/><path d="M12 2.5a14.5 14.5 0 0 1 0 19 14.5 14.5 0 0 1 0-19Z"/>',
  shield:
    '<path d="M12 2.5 4.5 5.8v5.6c0 5.1 3.2 8.8 7.5 10.1 4.3-1.3 7.5-5 7.5-10.1V5.8L12 2.5Z"/><polyline points="9 12 11 14 15.2 9.8"/>',
  key: '<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 9.3-9.3"/><path d="m17 6 3 3"/><path d="m14 9 2.5 2.5"/>',
  sparkles:
    '<path d="m12 3 1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9L12 3Z"/><path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9L19 15Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2.2M12 19.8V22M4.93 4.93l1.56 1.56M17.51 17.51l1.56 1.56M2 12h2.2M19.8 12H22M4.93 19.07l1.56-1.56M17.51 6.49l1.56-1.56"/>',
  moon: '<path d="M20.4 14.6A8.5 8.5 0 0 1 9.4 3.6a8.5 8.5 0 1 0 11 11Z"/>',
  contrast: '<circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 1 0 18Z" fill="currentColor"/>',
  moreVertical:
    '<circle cx="12" cy="5" r="1.4" fill="currentColor"/><circle cx="12" cy="12" r="1.4" fill="currentColor"/><circle cx="12" cy="19" r="1.4" fill="currentColor"/>',
  paperclip:
    '<path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>',
  fileText:
    '<path d="M14 2.5H6.5A2.5 2.5 0 0 0 4 5v14a2.5 2.5 0 0 0 2.5 2.5h11A2.5 2.5 0 0 0 20 19V8.5L14 2.5Z"/><polyline points="14 2.5 14 8.5 20 8.5"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="14" y2="17"/>',
  cloud: '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>',
  database:
    '<ellipse cx="12" cy="5" rx="8.5" ry="3"/><path d="M3.5 5v14c0 1.66 3.8 3 8.5 3s8.5-1.34 8.5-3V5"/><path d="M3.5 12c0 1.66 3.8 3 8.5 3s8.5-1.34 8.5-3"/>',
  alertTriangle:
    '<path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
  clock: '<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/>',
  contactless:
    '<path d="M8.5 14.5a4 4 0 0 0 0-5"/><path d="M11.5 16.5a7 7 0 0 0 0-9"/><path d="M14.5 18.5a10 10 0 0 0 0-13"/>'
};

export function renderIconSvg(
  name: VectorIconName,
  size = 15,
  className = 'sp-svg-icon'
): string {
  return `<svg class="${className}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${PATHS[name]}</svg>`;
}

export function categoryIconName(category: VaultCategory): VectorIconName {
  switch (category) {
    case 'PAYMENT_CARD':
      return 'paymentCard';
    case 'BANK_ACCOUNT':
      return 'bankAccount';
    case 'INSURANCE_CARD':
      return 'insuranceCard';
    case 'MEMBERSHIP_CARD':
      return 'membershipCard';
    case 'IDENTITY':
      return 'identity';
    case 'OFFICIAL_DOCUMENT':
      return 'officialDocument';
    case 'SECURE_NOTE':
      return 'secureNote';
  }
}
