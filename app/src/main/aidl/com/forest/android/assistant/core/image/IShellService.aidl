// 以 shell 身份执行截屏命令的 UserService 接口（Shizuku）
// 注意：实现类必须直接继承 IShellService.Stub（即 IBinder），不能是 Android Service。
package com.forest.android.assistant.core.image;

interface IShellService {
    /** 执行 screencap 并写入指定路径；返回是否成功 */
    boolean screencap(String path);

    /** Shizuku 请求销毁该 UserService 进程 */
    void destroy();
}
