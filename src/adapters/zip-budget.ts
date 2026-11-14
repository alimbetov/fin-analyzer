/**
 * Preliminary ZIP central-directory budget for untrusted XLSX files.
 * This is a metadata/resource gate, not an absolute decompression sandbox.
 * ExcelJS still requires an isolated worker + cancellation for hardened deployments.
 */
const MAX_ARCHIVE_BYTES = 2_000_000;
const MAX_UNPACKED_BYTES = 20_000_000;
const MAX_ENTRY_BYTES = 8_000_000;
const MAX_ENTRY_COUNT = 250;
const MAX_RATIO = 1000;

function fail(reason: string): never {
  throw new Error("Недопустимый XLSX ZIP: " + reason);
}

export function enforceZipBudget(input: ArrayBuffer): void {
  if (input.byteLength === 0 || input.byteLength > MAX_ARCHIVE_BYTES) {
    fail("размер архива вне допустимого диапазона (до 2 МБ)");
  }
  const view = new DataView(input);
  const bytes = new Uint8Array(input);
  const read16 = (offset: number) => view.getUint16(offset, true);
  const read32 = (offset: number) => view.getUint32(offset, true);

  // The EOCD may be followed by a comment of at most 65535 bytes.
  let end = -1;
  const min = Math.max(0, bytes.length - 22 - 65535);
  for (let offset = bytes.length - 22; offset >= min; offset--) {
    if (read32(offset) === 0x06054b50 &&
      offset + 22 + read16(offset + 20) === bytes.length) {
      end = offset;
      break;
    }
  }
  if (end === -1) fail("не найден корректный конец ZIP");

  const entries = read16(end + 10);
  const onDisk = read16(end + 8);
  const directorySize = read32(end + 12);
  const directoryStart = read32(end + 16);

  if (read16(end + 4) !== 0 || read16(end + 6) !== 0 || onDisk !== entries ||
      entries === 0 || entries > MAX_ENTRY_COUNT || entries === 0xffff ||
      directorySize === 0xffffffff || directoryStart === 0xffffffff ||
      directoryStart + directorySize !== end) {
    fail("многотомный, ZIP64 или некорректный центральный каталог");
  }

  let position = directoryStart;
  let totalSize = 0;
  const names = new Set<string>();
  const decode = new TextDecoder("utf-8", { fatal: true });

  for (let i = 0; i < entries; i++) {
    if (position + 46 > end || read32(position) !== 0x02014b50) {
      fail("нарушена структура записи каталога");
    }
    const flags = read16(position + 8);
    const method = read16(position + 10);
    const compressed = read32(position + 20);
    const expanded = read32(position + 24);
    const filenameLength = read16(position + 28);
    const extraLength = read16(position + 30);
    const commentLength = read16(position + 32);
    const localOffset = read32(position + 42);
    const next = position + 46 + filenameLength + extraLength + commentLength;
    if (next > end || !filenameLength) fail("повреждённая запись каталога");
    if ((flags & 0x1) !== 0 || ![0, 8].includes(method)) {
      fail("шифрование или неподдерживаемое сжатие");
    }
    if (compressed === 0xffffffff || expanded === 0xffffffff ||
        localOffset === 0xffffffff || expanded > MAX_ENTRY_BYTES ||
        (compressed === 0 && expanded > 0) ||
        (compressed > 0 && expanded / compressed > MAX_RATIO)) {
      fail("превышен бюджет распаковки отдельной записи");
    }
    totalSize += expanded;
    if (totalSize > MAX_UNPACKED_BYTES) fail("превышен общий бюджет распаковки");
    if (localOffset + 30 > directoryStart || read32(localOffset) !== 0x04034b50) {
      fail("повреждён локальный заголовок ZIP");
    }

    let filename: string;
    try {
      filename = decode.decode(bytes.subarray(position + 46, position + 46 + filenameLength));
    } catch {
      fail("некорректное имя ZIP-файла");
    }
    if (filename.startsWith("/") || filename.includes("\\") ||
        filename.split("/").includes("..") || names.has(filename)) {
      fail("небезопасное или дублированное имя ZIP-записи");
    }
    names.add(filename);
    position = next;
  }
  if (position !== end || !names.has("[Content_Types].xml") || !names.has("xl/workbook.xml")) {
    fail("неверный состав XLSX");
  }
}
