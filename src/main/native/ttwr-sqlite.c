#define UNICODE
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/* A 32-bit bootstrap which starts either architecture of Java as a child process. */
#define PCAP 4096
#define CCAP 32768
#define MAX_CANDIDATES 128
typedef struct { wchar_t path[PCAP]; wchar_t source[80]; } Candidate;
static Candidate candidates[MAX_CANDIDATES];
static int candidate_count,quiet;
static wchar_t root[PCAP],data_dir[PCAP],log_path[PCAP];
static int first_profile=0;

static int join(wchar_t *out,const wchar_t *base,const wchar_t *suffix){return swprintf(out,PCAP,L"%ls\\%ls",base,suffix)>0;}
static int exists_file(const wchar_t *p){DWORD a=GetFileAttributesW(p);return a!=INVALID_FILE_ATTRIBUTES&&!(a&FILE_ATTRIBUTE_DIRECTORY);}
static void log_line(const wchar_t *text){
    HANDLE h=CreateFileW(log_path,FILE_APPEND_DATA,FILE_SHARE_READ|FILE_SHARE_WRITE,NULL,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
    if(h==INVALID_HANDLE_VALUE)return;
    int count=WideCharToMultiByte(CP_UTF8,0,text,-1,NULL,0,NULL,NULL);
    if(count>0){char *utf=(char*)malloc((size_t)count);if(utf){DWORD written;WideCharToMultiByte(CP_UTF8,0,text,-1,utf,count,NULL,NULL);WriteFile(h,utf,(DWORD)count-1,&written,NULL);WriteFile(h,"\r\n",2,&written,NULL);free(utf);}}
    CloseHandle(h);
}
static int fail(const wchar_t *message,DWORD code){
    wchar_t text[8192];swprintf(text,8192,L"%ls\nWindows code: %lu\nЖурнал: %ls",message,(unsigned long)code,log_path);log_line(text);
    if(!quiet)MessageBoxW(NULL,text,L"TTWR SQLite",MB_OK|MB_ICONERROR);return 1;
}
/* Microsoft command-line quoting, including spaces, quotes and trailing backslashes. */
static int append_arg(wchar_t *command,const wchar_t *arg){
    size_t p=wcslen(command);if(p&&p+1<CCAP)command[p++]=L' ';
    if(p+2>=CCAP)return 0;command[p++]=L'"';
    while(*arg){
        size_t slashes=0;while(*arg==L'\\'){slashes++;arg++;}
        size_t emit=(*arg==L'"'||!*arg)?slashes*2:slashes;
        while(emit--){if(p+2>=CCAP)return 0;command[p++]=L'\\';}
        if(!*arg)break;
        if(*arg==L'"'){if(p+3>=CCAP)return 0;command[p++]=L'\\';}
        if(p+2>=CCAP)return 0;command[p++]=*arg++;
    }
    if(p+2>=CCAP)return 0;command[p++]=L'"';command[p]=0;return 1;
}
static void add_executable(const wchar_t *path,const wchar_t *source){
    wchar_t expanded[PCAP],absolute[PCAP];
    DWORD n=ExpandEnvironmentStringsW(path,expanded,PCAP);if(!n||n>=PCAP)return;
    size_t len=wcslen(expanded);if(len>1&&expanded[0]==L'"'&&expanded[len-1]==L'"'){memmove(expanded,expanded+1,(len-1)*sizeof(wchar_t));expanded[len-2]=0;}
    n=GetFullPathNameW(expanded,PCAP,absolute,NULL);if(!n||n>=PCAP||!exists_file(absolute))return;
    for(int i=0;i<candidate_count;i++)if(!_wcsicmp(candidates[i].path,absolute))return;
    if(candidate_count>=MAX_CANDIDATES)return;
    wcscpy(candidates[candidate_count].path,absolute);wcsncpy(candidates[candidate_count].source,source,79);candidate_count++;
}
static void add_home(const wchar_t *home,const wchar_t *source){
    wchar_t expanded[PCAP],path[PCAP];DWORD n=ExpandEnvironmentStringsW(home,expanded,PCAP);if(!n||n>=PCAP)return;
    size_t len=wcslen(expanded);if(len>1&&expanded[0]==L'"'&&expanded[len-1]==L'"'){memmove(expanded,expanded+1,(len-1)*sizeof(wchar_t));expanded[len-2]=0;}
    if(join(path,expanded,L"bin\\java.exe"))add_executable(path,source);
}
static void registry_tree(HKEY hive,const wchar_t *path,REGSAM view,int depth){
    HKEY key;if(depth>5||RegOpenKeyExW(hive,path,0,KEY_READ|view,&key)!=ERROR_SUCCESS)return;
    const wchar_t *names[]={L"JavaHome",L"Path",L"InstallationPath"};
    for(unsigned i=0;i<sizeof(names)/sizeof(names[0]);i++){
        wchar_t home[PCAP];DWORD type=0,bytes=sizeof(home);LONG rc=RegQueryValueExW(key,names[i],NULL,&type,(LPBYTE)home,&bytes);
        if(rc==ERROR_SUCCESS&&(type==REG_SZ||type==REG_EXPAND_SZ)&&bytes<sizeof(home)){home[bytes/sizeof(wchar_t)]=0;add_home(home,view==KEY_WOW64_32KEY?L"registry-32":L"registry-64");}
    }
    for(DWORD index=0;;index++){
        wchar_t child[256],nested[PCAP];DWORD count=256;LONG rc=RegEnumKeyExW(key,index,child,&count,NULL,NULL,NULL,NULL);
        if(rc==ERROR_NO_MORE_ITEMS)break;if(rc!=ERROR_SUCCESS)continue;
        if(join(nested,path,child))registry_tree(hive,nested,view,depth+1);
    }
    RegCloseKey(key);
}
static void collect_candidates(void){
    wchar_t path[PCAP],value[PCAP];
    if(join(path,root,L"runtime\\bin\\java.exe"))add_executable(path,L"bundled-runtime");
    const wchar_t *envs[]={L"JAVA_HOME",L"JRE_HOME"};
    for(unsigned i=0;i<2;i++){DWORD n=GetEnvironmentVariableW(envs[i],value,PCAP);if(n&&n<PCAP)add_home(value,envs[i]);}
    DWORD n=SearchPathW(NULL,L"java.exe",NULL,PCAP,path,NULL);if(n&&n<PCAP)add_executable(path,L"PATH");
    const wchar_t *keys[]={L"SOFTWARE\\JavaSoft",L"SOFTWARE\\Eclipse Adoptium",L"SOFTWARE\\AdoptOpenJDK",L"SOFTWARE\\Microsoft\\JDK",L"SOFTWARE\\Azul Systems\\Zulu"};
    HKEY hives[]={HKEY_CURRENT_USER,HKEY_LOCAL_MACHINE};REGSAM views[]={KEY_WOW64_64KEY,KEY_WOW64_32KEY};
    for(unsigned h=0;h<2;h++)for(unsigned v=0;v<2;v++)for(unsigned k=0;k<sizeof(keys)/sizeof(keys[0]);k++)registry_tree(hives[h],keys[k],views[v],0);
}
static int probe(const Candidate *candidate,int *major,int *bits){
    wchar_t command[CCAP]=L"";char output[32768];DWORD used=0;
    if(!append_arg(command,candidate->path)||!append_arg(command,L"-XshowSettings:properties")||!append_arg(command,L"-version"))return 0;
    SECURITY_ATTRIBUTES security={sizeof(security),NULL,TRUE};HANDLE reader=NULL,writer=NULL;
    if(!CreatePipe(&reader,&writer,&security,0))return 0;
    SetHandleInformation(reader,HANDLE_FLAG_INHERIT,0);
    HANDLE input=CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,NULL);
    STARTUPINFOW start;PROCESS_INFORMATION process;ZeroMemory(&start,sizeof(start));start.cb=sizeof(start);start.dwFlags=STARTF_USESTDHANDLES;start.hStdOutput=writer;start.hStdError=writer;start.hStdInput=input;
    BOOL launched=CreateProcessW(candidate->path,command,NULL,NULL,TRUE,CREATE_NO_WINDOW,NULL,root,&start,&process);
    CloseHandle(writer);if(input!=INVALID_HANDLE_VALUE)CloseHandle(input);
    if(!launched){CloseHandle(reader);log_line(L"Java candidate could not be started");return 0;}
    DWORD began=GetTickCount();int timed_out=0;
    for(;;){
        DWORD available=0;
        while(PeekNamedPipe(reader,NULL,0,NULL,&available,NULL)&&available){char buffer[2048];DWORD got=0;if(!ReadFile(reader,buffer,available>sizeof(buffer)?sizeof(buffer):available,&got,NULL)||!got)break;DWORD copy=got;if(copy>sizeof(output)-1-used)copy=(DWORD)sizeof(output)-1-used;if(copy){memcpy(output+used,buffer,copy);used+=copy;}}
        if(WaitForSingleObject(process.hProcess,20)==WAIT_OBJECT_0){
            if(!PeekNamedPipe(reader,NULL,0,NULL,&available,NULL)||!available)break;
        }
        if(GetTickCount()-began>10000){TerminateProcess(process.hProcess,124);WaitForSingleObject(process.hProcess,2000);timed_out=1;break;}
    }
    output[used]=0;DWORD code=1;GetExitCodeProcess(process.hProcess,&code);CloseHandle(reader);CloseHandle(process.hThread);CloseHandle(process.hProcess);
    if(timed_out||code!=0)return 0;
    char *version=strstr(output,"java.version = "),*model=strstr(output,"sun.arch.data.model = ");
    if(!version||!model)return 0;version+=strlen("java.version = ");model+=strlen("sun.arch.data.model = ");
    *major=!strncmp(version,"1.",2)?atoi(version+2):atoi(version);*bits=atoi(model);
    return *major>=8&&(*bits==32||*bits==64);
}
static int run_java(const Candidate *selected,int major,int bits,int check){
    wchar_t command[CCAP]=L"",classpath[PCAP*2],property[PCAP+80],java_log[PCAP],seed[PCAP],message[8192];
    if(!append_arg(command,selected->path)||!append_arg(command,L"-Xms64m")||!append_arg(command,bits==32?L"-Xmx512m":L"-Xmx1024m"))return fail(L"Слишком длинная команда запуска",0);
    if(major>=24&&!append_arg(command,L"--enable-native-access=ALL-UNNAMED"))return 1;
    if(!append_arg(command,first_profile?L"-Dttwr.firstRun=true":L"-Dttwr.firstRun=false"))return 1;
    append_arg(command,L"-Djava.net.preferIPv4Stack=true");swprintf(property,PCAP+80,L"-Dttwr.home=%ls",root);if(!append_arg(command,property))return 1;
    swprintf(property,PCAP+80,L"-Dttwr.seed=%ls\\app\\seed",root);if(!append_arg(command,property))return 1;
    swprintf(classpath,PCAP*2,L"%ls\\app\\ttwr.dat;%ls\\app\\lib\\*",root,root);
    if(!append_arg(command,L"-cp")||!append_arg(command,classpath)||!append_arg(command,check?L"ttr.SqliteCatalogTool":L"ttr.PortableStart"))return fail(L"Слишком длинная команда запуска",0);
    if(check){join(seed,root,L"app\\seed\\Players.sqlite");if(!append_arg(command,L"check")||!append_arg(command,seed))return 1;}
    join(java_log,data_dir,L"launcher-java.log");
    SECURITY_ATTRIBUTES security={sizeof(security),NULL,TRUE};
    HANDLE output=CreateFileW(java_log,GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
    if(output==INVALID_HANDLE_VALUE)return fail(L"Не удалось создать журнал Java рядом с программой",GetLastError());
    HANDLE input=CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,NULL);
    STARTUPINFOW start;PROCESS_INFORMATION process;ZeroMemory(&start,sizeof(start));start.cb=sizeof(start);start.dwFlags=STARTF_USESTDHANDLES;start.hStdOutput=output;start.hStdError=output;start.hStdInput=input;
    BOOL launched=CreateProcessW(selected->path,command,NULL,NULL,TRUE,CREATE_NO_WINDOW,NULL,root,&start,&process);DWORD error=GetLastError();CloseHandle(output);if(input!=INVALID_HANDLE_VALUE)CloseHandle(input);
    if(!launched)return fail(L"Не удалось запустить выбранную Java",error);
    WaitForSingleObject(process.hProcess,INFINITE);DWORD code=1;GetExitCodeProcess(process.hProcess,&code);CloseHandle(process.hThread);CloseHandle(process.hProcess);
    swprintf(message,8192,L"Java process exit=%lu",(unsigned long)code);log_line(message);
    if(code!=0){swprintf(message,8192,L"Java завершилась с ошибкой %lu.\nПодробности: %ls",(unsigned long)code,java_log);if(!quiet)MessageBoxW(NULL,message,L"TTWR SQLite",MB_OK|MB_ICONERROR);}
    return code==0?0:1;
}
int WINAPI WinMain(HINSTANCE instance,HINSTANCE previous,LPSTR args,int show){
    (void)instance;(void)previous;(void)args;(void)show;
    int argc=0;wchar_t **argv=CommandLineToArgvW(GetCommandLineW(),&argc);int check=argc>1&&!wcscmp(argv[1],L"--check-runtime");quiet=check;if(argv)LocalFree(argv);
    DWORD count=GetModuleFileNameW(NULL,root,PCAP);if(!count||count>=PCAP)return fail(L"Не удалось определить папку программы",GetLastError());
    wchar_t *slash=wcsrchr(root,L'\\');if(!slash)return fail(L"Некорректный путь программы",0);if(slash==root+2&&root[1]==L':')slash[1]=0;else *slash=0;
    if(!join(data_dir,root,L"data")||!join(log_path,data_dir,L"launcher.log"))return 1;
    first_profile=CreateDirectoryW(data_dir,NULL)!=0;
    if(!first_profile&&GetLastError()!=ERROR_ALREADY_EXISTS)return fail(L"Папка программы недоступна для записи. Перенесите весь комплект в доступную папку.",GetLastError());
    log_line(L"TTWR SQLite launcher 1.2, native x86 bootstrap");
    wchar_t archive[PCAP];join(archive,root,L"app\\ttwr.dat");if(!exists_file(archive))return fail(L"Не найден app\\ttwr.dat. Распакуйте весь комплект, а не только EXE.",0);
    collect_candidates();
    for(int i=0;i<candidate_count;i++){
        wchar_t info[8192];swprintf(info,8192,L"Candidate source=%ls path=%ls",candidates[i].source,candidates[i].path);log_line(info);
        int major=0,bits=0;
        if(!probe(&candidates[i],&major,&bits)){log_line(L"Candidate rejected: Java 8+ could not be verified");continue;}
        swprintf(info,8192,L"Selected source=%ls major=%d bits=%d heap=%ls path=%ls",candidates[i].source,major,bits,bits==32?L"512m":L"1024m",candidates[i].path);log_line(info);
        return run_java(&candidates[i],major,bits,check);
    }
    return fail(L"Не найдена рабочая Java 8 или новее.\nПоложите папку runtime рядом с EXE либо установите Java x64 или x32.\nЗапускатель проверяет JAVA_HOME, JRE_HOME, PATH и реестр Windows.",0);
}
