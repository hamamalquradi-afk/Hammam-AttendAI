declare module 'node:http' { const v:any; export = v; }
declare module 'node:crypto' { export function createHash(a:string):any; export function timingSafeEqual(a:any,b:any):boolean; export function randomBytes(size:number):any; export function randomUUID():string; }
declare var process:any; declare var Buffer:any;
declare module 'node:test' { export const test:any; }
declare module 'node:assert/strict' { const a:any; export default a; }
declare module 'node:fs/promises' { export function mkdir(path:string,opts?:any):Promise<any>; export function readFile(path:string,encoding:string):Promise<string>; export function rename(a:string,b:string):Promise<void>; export function writeFile(path:string,data:string,encoding:string):Promise<void>; export function rm(path:string,opts?:any):Promise<void>; }
declare module 'node:path' { export function dirname(path:string):string; export function resolve(...parts:string[]):string; }
declare module 'node:child_process' { export function spawn(command:string,args?:string[],opts?:any):any; }
