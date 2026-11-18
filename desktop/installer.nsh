; Choose install folder as <selected>\OllamaChat, create if missing.

!macro preInit
  SetRegView 64
  WriteRegExpandStr HKLM "${INSTALL_REGISTRY_KEY}" InstallLocation "$LOCALAPPDATA\Programs\${PRODUCT_FILENAME}"
  WriteRegExpandStr HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation "$LOCALAPPDATA\Programs\${PRODUCT_FILENAME}"
  WriteRegExpandStr HKLM "${INSTALL_REGISTRY_KEY}" QuietInstallLocation "$LOCALAPPDATA\Programs\${PRODUCT_FILENAME}"
  WriteRegExpandStr HKCU "${INSTALL_REGISTRY_KEY}" QuietInstallLocation "$LOCALAPPDATA\Programs\${PRODUCT_FILENAME}"
!macroend

Function .onVerifyInstDir
  Push $R0
  Push $R1

  StrCpy $R0 "$INSTDIR"
  StrCpy $R1 "$R0" "" -1
  StrCmp $R1 "\" 0 +2
    StrCpy $R0 "$R0" -1

  StrLen $R1 "\${PRODUCT_FILENAME}"
  StrCpy $R1 "$R0" "" -$R1
  StrCmp $R1 "\${PRODUCT_FILENAME}" done 0

  StrLen $R1 "${PRODUCT_FILENAME}"
  StrCpy $R1 "$R0" "" -$R1
  StrCmp $R1 "${PRODUCT_FILENAME}" done 0

  StrCpy $INSTDIR "$R0\${PRODUCT_FILENAME}"

done:
  CreateDirectory "$INSTDIR"
  Pop $R1
  Pop $R0
FunctionEnd
