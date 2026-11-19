/** Browser-side preflight only. Backend independently verifies magic, size and SHA. */
export async function validateEvidenceFile(file:File):Promise<void>{
  if(file.size<1||file.size>10*1024*1024)throw new Error('Размер документа должен быть от 1 байта до 10 МБ');
  if(file.name.length>240||/[/\\\x00-\x1f\x7f]/.test(file.name))
    throw new Error('Некорректное имя вложения');
  const header=new Uint8Array(await file.slice(0,8).arrayBuffer());
  const magic=(...bytes:number[])=>bytes.every((value,i)=>header[i]===value);
  const name=file.name.toLowerCase();
  const valid=(name.endsWith('.pdf')&&magic(0x25,0x50,0x44,0x46,0x2d))||
    (name.endsWith('.png')&&magic(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))||
    ((name.endsWith('.jpg')||name.endsWith('.jpeg'))&&magic(0xff,0xd8,0xff));
  if(!valid)throw new Error('Вложение не соответствует PDF, PNG или JPEG');
}
