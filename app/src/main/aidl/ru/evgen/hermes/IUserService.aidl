// The shell bridge Shizuku runs for us. `newProcess` is private in the Shizuku
// API, so the supported path is a UserService: Shizuku starts this service in a
// process owned by the shell user, and every call below runs as shell.
//
// The two transaction ids at the bottom are reserved by Shizuku's own lifecycle
// handling (it calls them when the service is stopped or replaced); a service
// without them leaks or wedges on the next bind.
package ru.evgen.hermes;

interface IUserService {
    String run(String command) = 1;
    void destroy() = 16777114;
    void exit() = 16777113;
}
