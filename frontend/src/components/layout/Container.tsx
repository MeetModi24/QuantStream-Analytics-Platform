import { ReactNode } from 'react';

interface ContainerProps {
  children: ReactNode;
}

export function Container({ children }: ContainerProps) {
  return (
    <main className="ml-16 min-h-[calc(100vh-4rem)] bg-background p-6">
      <div className="mx-auto max-w-[1920px]">{children}</div>
    </main>
  );
}
