export type PasteField = {fieldCode:string;title:string;fieldType:string};

export class ClipboardShapeError extends Error {
  constructor(message:string){super(message);this.name='ClipboardShapeError';}
}

/**
 * Conservative Excel import into a single-column financial form:
 *  - one column of values;
 *  - 2 columns (indicator code/title, value), validated against schema;
 *  - 3 columns (row number, indicator code/title, value), validated;
 *  - a single horizontal row of values.
 * Never guess a value column or silently truncate an overflowing range.
 */
export function parseExcelPaste(text:string,start:number,fields:PasteField[]):string[]{
  const data=text.replace(/\r\n?/g,'\n').replace(/\n$/,'');
  if(!data.trim())throw new ClipboardShapeError('Буфер Excel пуст');
  const grid=data.split('\n').map(line=>line.split('\t'));
  if(grid.length>1000)throw new ClipboardShapeError('Слишком большой диапазон');
  const width=grid[0].length;
  if(grid.some(row=>row.length!==width))
    throw new ClipboardShapeError('Диапазон Excel имеет неодинаковую ширину строк');
  if(start<0||start>=fields.length)
    throw new ClipboardShapeError('Неверная начальная ячейка формы');

  const equalLabel=(label:string,field:PasteField)=>
    [field.title,field.fieldCode].some(s=>s.trim().toLocaleLowerCase()===label.trim().toLocaleLowerCase());

  let values:string[];
  if(width===1)values=grid.map(row=>row[0]);
  else if(width===2&&grid.every((row,i)=>fields[start+i]&&equalLabel(row[0],fields[start+i])))
    values=grid.map(row=>row[1]);
  else if(width===3&&grid.every((row,i)=>fields[start+i]&&
    row[0].trim()===String(start+i+1)&&equalLabel(row[1],fields[start+i])))
    values=grid.map(row=>row[2]);
  else if(grid.length===1&&grid[0].every(v=>v.trim()!=='')&&
    (width!==3||!/^\d+$/.test(grid[0][0].trim())))
    values=grid[0];
  else
    throw new ClipboardShapeError(
      'Вставляйте один столбец значений либо № + точное название + значение. '+
      'Нельзя угадывать финансовые показатели по произвольным столбцам.');

  if(start+values.length>fields.length)
    throw new ClipboardShapeError('Диапазон выходит за последнюю строку формы');
  if(values.some(v=>/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(v)))
    throw new ClipboardShapeError('Недопустимые управляющие символы');
  return values;
}
