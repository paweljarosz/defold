# Setup Engine - Linux

(Setup instructions for the editor [here](/editor/README.md)).

## Required Software

### Required Software - Java JDK 11

Download and install the latest JDK 11 (11.0.15 or later) release from either of these locations:

* [Adoptium/Temurin](https://github.com/adoptium/temurin11-binaries/releases) - The Adoptium Working Group promotes and supports high-quality runtimes and associated technology for use across the Java ecosystem
* [Microsoft OpenJDK builds](https://docs.microsoft.com/en-us/java/openjdk/download#openjdk-11) - The Microsoft Build of OpenJDK is a no-cost distribution of OpenJDK that's open source and available for free for anyone to deploy anywhere
* or from apt-get:
```
> sudo apt-get install openjdk-11-jdk
```

When Java is installed you may also add need to add java to your PATH and export JAVA_HOME:

```sh
> nano ~/.bashrc

export JAVA_HOME=<JAVA_INSTALL_PATH>
export PATH=$JAVA_HOME/bin:$PATH
```

Verify that Java is installed and working:

```sh
> javac -version
```


### Required Software - Python 2

You need a 64 bit Python 2 version to build the engine and tools. The latest tested on all platforms is Python 2.7.16. You also need `easy_install` to install additional packages. Install Python 2 using:

```sh
> sudo apt install python2-minimal python-setuptools
```

Since our scripts use `python`, you'll need to set it up somehow.
One way is using an alias:

```sh
# in ~/.bashrc
alias python='python2.7'
```

Another way is to use [update-alternatives](https://www.google.com/search?client=firefox-b-d&q=linux+update-alternatives+python) to select version.


### Required Software - Additional tools

You need additional files and tools to be able to build and work with Defold on Linux:

**Development files**
* **libxi-dev** - X11 Input extension library
* **libxext-dev** - X11 Miscellaneous extensions library
* **x11proto-xext-dev** - X11 various extension wire protocol
* **freeglut3-dev** - OpenGL Utility Toolkit development files
* **libglu1-mesa-dev** + libgl1-mesa-dev + mesa-common-dev - Mesa OpenGL development files
* **libcurl4-openssl-dev** - Development files and documentation for libcurl
* **uuid-dev** - Universally Unique ID library
* **libopenal-dev** - Software implementation of the OpenAL audio API
* **libncurses5** -  Needed by clang

**Tools**
* **build-essential** - Compilers
* **rpm** - package manager for RPM
* **git** - Fast, scalable, distributed revision control system
* **curl** - Command line tool for transferring data with URL syntax
* **autoconf** - Automatic configure script builder
* **libtool** - Generic library support script
* **automake** - Tool for generating GNU Standards-compliant Makefiles
* **cmake** - Cross-platform, open-source make system
* **tofrodos** - Converts DOS <-> Unix text files
* **valgrind** - Instrumentation framework for building dynamic analysis tools

Download and install using `apt-get`:

```sh
> sudo apt-get install -y --no-install-recommends libssl-dev openssl libtool autoconf automake build-essential uuid-dev libxi-dev libopenal-dev libgl1-mesa-dev libglw1-mesa-dev freeglut3-dev libncurses5
```

---

## Optional Software

It is recommended but not required that you install the following software:

* **wget** + **curl** - for downloading packages
* **7z** - for extracting packages (archives and binaries)
* **ccache** - for faster compilations of source code
* **cmake** for easier building of external projects
* **patch** for easier patching on windows (when building external projects)
* **snapd** for installing snap packages
* **ripgrep** for faster search

Quick and easy install:

```sh
> sudo apt-get install wget curl p7zip ccache
```

Configure `ccache` by running ([source](https://ccache.samba.org/manual.html))

```sh
> ccache --max-size=5G
```

Install snapd package manager:

```sh
> sudo apt install snapd
```

Install ripgrep:

```sh
> sudo snap install ripgrep --classic
```

---

## Optional Setup

### Optional Setup - Command Prompt

It's useful to modify your command prompt to show the status of the repo you're in.
E.g. it makes it easier to keep the git branches apart.

You do this by editing the `PS1` variable. Put it in the recommended config for your system (e.g. `.profile` or `.bashrc`)
Here's a very small improvement on the default prompt, whic shows you the time of the last command, as well as the current git branch name and its status:

```sh
git_branch() {
    git branch 2> /dev/null | sed -e '/^[^*]/d' -e 's/* \(.*\)/(\1)/'
}
acolor() {
  [[ -n $(git status --porcelain=v2 2>/dev/null) ]] && echo 31 || echo 33
}
export PS1='\t \[\033[32m\]\w\[\033[$(acolor)m\] $(git_branch)\[\033[00m\] $ '
```


## WSL (Windows Subsystem for Linux)

It is possible to build Linux targets using WSL 1.

Install relevant packages (git, java, python, clang etc) using `./scripts/linux/install_wsl_packages.sh`.
If also updates your `~/.bashrc` with updated paths.

### Git clone into a mounted folder

In order to get the proper username of your files, we need to setup WSL for this.
Otherwise the git clone won't work in a mounted C: drive folder.

Open (or create) the config file:
```
sudo nano /etc/wsl.conf
```

Add these lines:
```
[automount]
options = "metadata"
```

And restart your WSL session


### X11

The script also sets the `DISPLAY=localhost:0.0` which allows you to connect to a local X server.

A popular choice is [VCXSRV](https://sourceforge.net/projects/vcxsrv/)

