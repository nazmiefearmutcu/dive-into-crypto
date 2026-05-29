' ============================================================
' Trading Bot v1 - Desktop Shortcut Creator
' ============================================================
' Double-click this .vbs file. A shortcut named "Trading Bot v1"
' is created on the desktop. Click = the application opens.
' ============================================================

Set oWS = WScript.CreateObject("WScript.Shell")
Set oFS = WScript.CreateObject("Scripting.FileSystemObject")

' The folder this .vbs file is in (packaging/)
sScriptDir = oFS.GetParentFolderName(WScript.ScriptFullName)
sProjectRoot = oFS.GetParentFolderName(sScriptDir)
sExe = sProjectRoot & "\dist\TradingBotV1\TradingBotV1.exe"
sIcon = sScriptDir & "\tbv1.ico"

If Not oFS.FileExists(sExe) Then
    MsgBox "TradingBotV1.exe not found:" & vbCrLf & sExe & vbCrLf & vbCrLf & _
           "You need to run packaging\build_windows.bat first.", _
           vbCritical, "Trading Bot v1 - Shortcut could not be created"
    WScript.Quit 1
End If

sDesktop = oWS.SpecialFolders("Desktop")
sLnk = sDesktop & "\Trading Bot v1.lnk"

Set oLnk = oWS.CreateShortcut(sLnk)
oLnk.TargetPath = sExe
oLnk.WorkingDirectory = oFS.GetParentFolderName(sExe)
oLnk.IconLocation = sIcon
oLnk.Description = "Trading Bot v1 - Indicator Consensus Trading System"
oLnk.WindowStyle = 1
oLnk.Save

MsgBox "Desktop shortcut created!" & vbCrLf & vbCrLf & _
       "Shortcut: " & sLnk & vbCrLf & _
       "Target:   " & sExe, _
       vbInformation, "Trading Bot v1"
