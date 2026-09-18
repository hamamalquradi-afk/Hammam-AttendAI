declare module 'node:http' { const v:any; export = v; }
declare module 'node:crypto' { export function createHash(a:string):any; export function timingSafeEqual(a:any,b:any):boolean; }
declare var process:any; declare var Buffer:any;
declare module 'node:test' { export const test:any; }
declare module 'node:assert/strict' { const a:any; export default a; }
