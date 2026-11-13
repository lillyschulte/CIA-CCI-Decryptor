# Batch CIA CCI Decryptor (Linux)
Batch decrypting for Nintendo 3DS games and applications (.3ds|.cci, .cia) on Linux.

This is a Linux port of [rcyggdra/decryptor](https://github.com/rcyggdra/decryptor), which runs on Windows only. The Windows `.bat` scripts are replaced by a bash script, and all tools are built or downloaded as native Linux binaries.

Azahar makes use of the `.cci` extension, which is the true name of the format used by `.3ds` files.

Batch CIA CCI Decryptor is a rewritten version of the Batch CIA 3DS Decryptor by matiffeder.

Original thread: https://gbatemp.net/threads/batch-cia-3ds-decryptor-a-simple-batch-file-to-decrypt-cia-3ds.512385/

## Features
* DLC/Patch CIA > Decrypted CIA, able to install in Azahar
* 3DS Games > Decrypted and trimmed CCI, so it is smaller
* CIA Games > Decrypted CIA
* Auto detect CIA type (DLC/Patch/Game)
* Skips files that are already decrypted or have `decrypted` in their name

## Requirements
* Linux x86_64
* Rust/cargo, `curl` and `unzip`

Install Rust with your package manager or from https://rustup.rs:
```
sudo dnf install cargo unzip      # Fedora
sudo apt install cargo unzip      # Debian/Ubuntu
sudo pacman -S rust unzip         # Arch
```

## Setup
```
git clone https://github.com/lillyschulte/CIA-CCI-Decryptor
cd decryptor
./setup.sh
```
`setup.sh` builds `ctrdecrypt` and `dlchelper` into `bin/` and downloads the Linux builds of `ctrtool` and `makerom` from [Project_CTR](https://github.com/3DSGuy/Project_CTR/releases). Existing `ctrtool`/`makerom` binaries in `bin/` are kept; delete them to download again. If they are not in `bin/`, the tools are also looked up on your `PATH`.

## Usage
Copy your CIA, 3DS or CCI files into the folder containing `decrypt.sh` and run:
```
./decrypt.sh
```
Or point it at another folder:
```
./decrypt.sh /path/to/games
```

Output files are written next to the originals:

| Input | Output |
| --- | --- |
| `Game.3ds` / `Game.cci` | `Game-decrypted.cci` |
| Game CIA | `Game-decrypted.cia` |
| Update CIA | `Game (Update)-decrypted.cia` |
| DLC CIA | `Game (DLC)-decrypted.cia` |

### Debug mode
```
./decrypt.sh --debug
```
Shows each command and its output, writes the `ctrdecrypt` log to `log.txt`, and processes files even if they already look decrypted. Use this if a file does not produce any output.

## Android
An Android app (arm64, e.g. AYN Thor) lives in [`android/`](android/README.md).

## Seeds
Some titles use seed crypto. `ctrdecrypt` reads seeds from `bin/seeddb.bin` and, if a seed is missing, tries to fetch it from Nintendo's servers (this needs an internet connection).

## Credits
* `Batch CIA CCI Decryptor` (Windows) - [rcyggdra](https://github.com/rcyggdra/decryptor)
* `Batch CIA 3DS Decryptor` - [matiffeder](https://github.com/matiffeder/3DS-stuff)
* `ctrtool/makerom` - [3DSGuy](https://github.com/3DSGuy/Project_CTR)
* `seeddb.bin` - [ihaveamac](https://github.com/ihaveamac/3DS-rom-tools/tree/master/seeddb)
* `ctrdecrypt` - [shijimasoft](https://github.com/shijimasoft/ctrdecrypt) & [R-YaTian](https://github.com/R-YaTian/ctrdecrypt)
* `dlchelper` - [R-YaTian](https://github.com/R-YaTian/3DS-Converters/tree/master/dlchelper)
